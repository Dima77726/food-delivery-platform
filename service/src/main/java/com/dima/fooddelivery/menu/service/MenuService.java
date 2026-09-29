package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.common.cache.CacheNames;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.persistence.DataAccessErrors;
import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuCategoryView;
import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.MenuItemSnapshot;
import com.dima.fooddelivery.menu.domain.MenuItemView;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.menu.persistence.MenuCategoryRepository;
import com.dima.fooddelivery.menu.persistence.MenuItemRepository;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Модуль Menu: витрина для посетителя и управление для владельца ресторана.
 *
 * <p>Публичный вход для других модулей — {@link #requireAvailableItem(Long, Long)}: модуль Cart
 * спрашивает здесь цену и доступность блюда вместо того, чтобы join'ить {@code menu_item} у себя.
 *
 * <p>Наружу сервис отдаёт только record'ы: {@link RestaurantMenu}, {@link MenuCategoryView},
 * {@link MenuItemView}, {@link MenuItemSnapshot}. Сущности {@link MenuCategory} и
 * {@link MenuItem} за границу транзакции не выходят — при {@code open-in-view: false}
 * отсоединённая сущность с ленивой связью в контроллере бесполезна и опасна.
 *
 * <p><b>Про flush.</b> Каждый изменяющий метод заканчивается явным сбросом контекста, и это
 * не перестраховка. Hibernate по умолчанию копит изменения до коммита, а бизнес-правила
 * этого модуля опираются на ограничения самой базы — частичные уникальные индексы
 * {@code uq_menu_category_active_name} и {@code uq_menu_item_active_name}. Без flush нарушение
 * всплыло бы не здесь, а при коммите транзакции: клиент получил бы 500 вместо понятного 409,
 * а перехватить исключение было бы уже негде. Второй, менее очевидный повод: Hibernate внутри
 * одного сброса выполняет все INSERT раньше всех UPDATE. Архивирование блюда и создание
 * нового с тем же именем, попав в один flush, нарушили бы уникальность — INSERT ушёл бы
 * до того, как старая строка помечена архивной.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private static final int DEFAULT_SORT_ORDER = 0;

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final RestaurantService restaurantService;

    // --- Витрина ------------------------------------------------------------------------------

    /**
     * Публичное меню — то, ради чего кэш и заводился: анонимных чтений здесь на порядки
     * больше, чем всех остальных запросов вместе.
     */
    @Cacheable(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional(readOnly = true)
    public RestaurantMenu getRestaurantMenu(Long restaurantId) {
        // Меню закрытого ресторана показать можно — нельзя только заказать.
        // Поэтому проверяется существование, а не активность.
        restaurantService.requireRestaurant(restaurantId);

        return RestaurantMenu.of(restaurantId, categoryRepository.findMenu(restaurantId), false);
    }

    /**
     * Меню владельца не кэшируется намеренно. Он только что нажал «сохранить» и обязан
     * увидеть результат немедленно, а не через десять минут. Кэшируется чтение массовое
     * и терпимое к задержке, а не то, что человек проверяет сразу после своего действия.
     */
    @Transactional(readOnly = true)
    public RestaurantMenu getManagedMenu(Long restaurantId) {
        restaurantService.requireRestaurant(restaurantId);

        return RestaurantMenu.of(restaurantId, categoryRepository.findMenu(restaurantId), true);
    }

    /**
     * @throws ResourceNotFoundException      если блюда нет в этом ресторане или оно архивное
     * @throws BusinessRuleViolationException если блюдо есть, но помечено недоступным
     */
    @Transactional(readOnly = true)
    public MenuItemSnapshot requireAvailableItem(Long restaurantId, Long menuItemId) {
        MenuItemSnapshot item = itemRepository.findOrderableInRestaurant(restaurantId, menuItemId)
                .orElseThrow(() -> {
                    log.warn(
                            "Позиция меню не найдена в ресторане: restaurantId={}, menuItemId={}",
                            restaurantId,
                            menuItemId
                    );

                    return new ResourceNotFoundException(
                            "Позиция меню с id=" + menuItemId + " не найдена в ресторане с id=" + restaurantId
                    );
                });

        if (!item.available()) {
            throw new BusinessRuleViolationException(
                    "Позиция меню с id=" + menuItemId + " сейчас недоступна для заказа"
            );
        }

        return item;
    }

    // --- Категории ----------------------------------------------------------------------------

    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public MenuCategoryView createCategory(Long restaurantId, String name, Integer sortOrder) {
        restaurantService.requireRestaurant(restaurantId);

        String trimmedName = name.trim();

        MenuCategory category = new MenuCategory(restaurantId, trimmedName, sortOrderOrDefault(sortOrder));

        withDuplicateNameGuard(
                () -> categoryRepository.saveAndFlush(category),
                "Категория с названием «" + trimmedName + "» в этом ресторане уже есть"
        );

        log.info("Создана категория меню: categoryId={}, restaurantId={}", category.getId(), restaurantId);

        return MenuCategoryView.withoutItems(category);
    }

    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public MenuCategoryView updateCategory(Long restaurantId, Long categoryId, String name, Integer sortOrder) {
        MenuCategory category = requireCategoryInRestaurant(restaurantId, categoryId);

        // Проверка в Java вместо AND is_archived = FALSE в UPDATE. JDBC-версия узнавала
        // об архивной категории по нулю изменённых строк — то есть по косвенному признаку,
        // который так же обозначал бы и исчезнувшую строку.
        if (category.isArchived()) {
            throw new BusinessRuleViolationException(
                    "Категорию с id=" + categoryId + " нельзя изменить: она архивирована"
            );
        }

        if (name != null) {
            category.setName(name.trim());
        }

        if (sortOrder != null) {
            category.setSortOrder(sortOrder);
        }

        withDuplicateNameGuard(
                categoryRepository::flush,
                "Категория с таким названием в этом ресторане уже есть"
        );

        return MenuCategoryView.withoutItems(category);
    }

    /**
     * Убирает категорию из меню вместе со всеми её блюдами.
     *
     * <p>Именно архивирование, а не DELETE. На {@code menu_item} ссылаются позиции заказов
     * с ON DELETE RESTRICT, поэтому удалить категорию, из которой хоть раз что-то заказали,
     * база просто не даст. И это правильно: заказ обязан помнить, что купил клиент.
     *
     * <p>Обход блюд теперь живёт в самом агрегате — {@link MenuCategory#archive()}. JDBC-версия
     * делала это двумя UPDATE подряд, по одному на таблицу.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public void archiveCategory(Long restaurantId, Long categoryId) {
        MenuCategory category = requireCategoryInRestaurant(restaurantId, categoryId);

        if (category.isArchived()) {
            throw new BusinessRuleViolationException("Категория с id=" + categoryId + " уже архивирована");
        }

        category.archive();
        categoryRepository.flush();

        log.info("Категория меню архивирована: categoryId={}, restaurantId={}", categoryId, restaurantId);
    }

    // --- Позиции ------------------------------------------------------------------------------

    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public MenuItemView createItem(
            Long restaurantId,
            Long categoryId,
            String name,
            String description,
            BigDecimal price,
            Integer sortOrder
    ) {
        MenuCategory category = requireCategoryInRestaurant(restaurantId, categoryId);

        if (category.isArchived()) {
            throw new BusinessRuleViolationException(
                    "Нельзя добавить блюдо в архивированную категорию с id=" + categoryId
            );
        }

        String trimmedName = name.trim();

        // Отдельного save для блюда нет: оно попадает в базу каскадом от категории.
        MenuItem item = category.addItem(trimmedName, description, price, sortOrderOrDefault(sortOrder));

        withDuplicateNameGuard(
                categoryRepository::flush,
                "Блюдо с названием «" + trimmedName + "» в этой категории уже есть"
        );

        log.info("Создано блюдо: menuItemId={}, categoryId={}", item.getId(), categoryId);

        return MenuItemView.of(item);
    }

    /**
     * Изменение цены не трогает существующие корзины и заказы: и {@code cart_item},
     * и {@code customer_order_item} хранят собственную копию цены на момент добавления.
     * Клиент заплатит ту цену, которую видел.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public MenuItemView updateItem(
            Long restaurantId,
            Long itemId,
            String name,
            String description,
            BigDecimal price,
            Integer sortOrder
    ) {
        MenuItem item = requireItemInRestaurant(restaurantId, itemId);

        if (item.isArchived()) {
            throw new BusinessRuleViolationException(
                    "Блюдо с id=" + itemId + " нельзя изменить: оно архивировано"
            );
        }

        if (name != null) {
            item.setName(name.trim());
        }

        if (description != null) {
            item.setDescription(description);
        }

        if (price != null) {
            item.setPrice(price);
        }

        if (sortOrder != null) {
            item.setSortOrder(sortOrder);
        }

        withDuplicateNameGuard(
                itemRepository::flush,
                "Блюдо с таким названием в этой категории уже есть"
        );

        return MenuItemView.of(item);
    }

    /**
     * Временное скрытие: блюдо закончилось и вернётся. В отличие от архивирования обратимо.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public MenuItemView setItemAvailability(Long restaurantId, Long itemId, boolean available) {
        MenuItem item = requireItemInRestaurant(restaurantId, itemId);

        if (item.isArchived()) {
            throw new BusinessRuleViolationException(
                    "Доступность блюда с id=" + itemId + " нельзя изменить: оно архивировано"
            );
        }

        item.setAvailable(available);
        itemRepository.flush();

        return MenuItemView.of(item);
    }

    @CacheEvict(cacheNames = CacheNames.RESTAURANT_MENU, key = "#restaurantId")
    @Transactional
    public void archiveItem(Long restaurantId, Long itemId) {
        MenuItem item = requireItemInRestaurant(restaurantId, itemId);

        if (item.isArchived()) {
            throw new BusinessRuleViolationException("Блюдо с id=" + itemId + " уже архивировано");
        }

        item.archive();

        // Сброс здесь обязателен, а не желателен: он освобождает имя в частичном уникальном
        // индексе. Без него следующее создание блюда с тем же названием ушло бы в базу
        // раньше этого UPDATE и упало бы на нарушении уникальности.
        itemRepository.flush();

        log.info("Блюдо архивировано: menuItemId={}, restaurantId={}", itemId, restaurantId);
    }

    // --- Вспомогательное ----------------------------------------------------------------------

    private MenuCategory requireCategoryInRestaurant(Long restaurantId, Long categoryId) {
        return categoryRepository.findByIdAndRestaurantId(categoryId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Категория с id=" + categoryId + " не найдена в ресторане с id=" + restaurantId
                ));
    }

    private MenuItem requireItemInRestaurant(Long restaurantId, Long itemId) {
        return itemRepository.findByIdAndCategory_RestaurantId(itemId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Блюдо с id=" + itemId + " не найдено в ресторане с id=" + restaurantId
                ));
    }

    /**
     * Уникальность имени проверяет база частичным индексом, а не предварительный SELECT:
     * он не спас бы от двух одновременных запросов с одним названием.
     *
     * <p>Ловится {@link DataIntegrityViolationException}, хотя JDBC-версия ловила более узкий
     * {@code DuplicateKeyException}. Причина в том, кто переводит исключение: у JdbcTemplate
     * это транслятор, разбирающий код ошибки драйвера, а на пути через JPA нарушение
     * ограничения приходит от Hibernate и превращается в общий
     * {@code DataIntegrityViolationException} без уточнения вида.
     *
     * <p>Поэтому вид ограничения уточняется по SQLSTATE — см. {@link DataAccessErrors}. Без
     * этой проверки в «имя занято» превратилось бы любое нарушение целостности, например
     * отрицательная цена, отсекаемая constraint'ом {@code chk_menu_item_price_positive}.
     */
    private void withDuplicateNameGuard(Runnable action, String message) {
        try {
            action.run();
        } catch (DataIntegrityViolationException exception) {
            if (DataAccessErrors.isUniqueViolation(exception)) {
                throw new BusinessRuleViolationException(message);
            }

            throw exception;
        }
    }

    private int sortOrderOrDefault(Integer sortOrder) {
        return sortOrder == null ? DEFAULT_SORT_ORDER : sortOrder;
    }
}
