package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.api.CreateMenuCategoryRequest;
import com.dima.fooddelivery.menu.api.CreateMenuItemRequest;
import com.dima.fooddelivery.menu.api.ManagedMenuCategoryResponse;
import com.dima.fooddelivery.menu.api.ManagedMenuItemResponse;
import com.dima.fooddelivery.menu.api.ManagedMenuResponse;
import com.dima.fooddelivery.menu.api.MenuResponseMapper;
import com.dima.fooddelivery.menu.api.RestaurantMenuResponse;
import com.dima.fooddelivery.menu.api.UpdateMenuCategoryRequest;
import com.dima.fooddelivery.menu.api.UpdateMenuItemRequest;
import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.MenuItemSnapshot;
import com.dima.fooddelivery.menu.persistence.MenuRepository;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Модуль Menu: витрина для клиента и управление для владельца ресторана.
 *
 * <p>Публичный вход для других модулей — {@link #requireAvailableItem(Long, Long)}: модуль Cart
 * спрашивает здесь цену и доступность блюда вместо того, чтобы join'ить {@code menu_item} у себя.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private static final int DEFAULT_SORT_ORDER = 0;

    private final MenuRepository menuRepository;
    private final RestaurantService restaurantService;

    // --- Витрина ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RestaurantMenuResponse getRestaurantMenu(Long restaurantId) {
        // Меню закрытого ресторана показать можно — нельзя только заказать.
        // Поэтому проверяется существование, а не активность.
        restaurantService.requireRestaurant(restaurantId);

        return MenuResponseMapper.toResponse(menuRepository.findMenu(restaurantId, false));
    }

    @Transactional(readOnly = true)
    public ManagedMenuResponse getManagedMenu(Long restaurantId) {
        restaurantService.requireRestaurant(restaurantId);

        return MenuResponseMapper.toManagedResponse(menuRepository.findMenu(restaurantId, true));
    }

    /**
     * @throws ResourceNotFoundException      если блюда нет в этом ресторане или оно архивное
     * @throws BusinessRuleViolationException если блюдо есть, но помечено недоступным
     */
    @Transactional(readOnly = true)
    public MenuItemSnapshot requireAvailableItem(Long restaurantId, Long menuItemId) {
        MenuItemSnapshot item = menuRepository.findItemInRestaurant(restaurantId, menuItemId)
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

    @Transactional
    public ManagedMenuCategoryResponse createCategory(Long restaurantId, CreateMenuCategoryRequest request) {
        restaurantService.requireRestaurant(restaurantId);

        Long categoryId = withDuplicateNameGuard(
                () -> menuRepository.insertCategory(
                        restaurantId,
                        request.name().trim(),
                        sortOrderOrDefault(request.sortOrder())
                ),
                "Категория с названием «" + request.name().trim() + "» в этом ресторане уже есть"
        );

        log.info("Создана категория меню: categoryId={}, restaurantId={}", categoryId, restaurantId);

        return MenuResponseMapper.toManagedResponse(requireCategory(categoryId));
    }

    @Transactional
    public ManagedMenuCategoryResponse updateCategory(
            Long restaurantId,
            Long categoryId,
            UpdateMenuCategoryRequest request
    ) {
        requireCategoryInRestaurant(restaurantId, categoryId);

        int updated = withDuplicateNameGuard(
                () -> menuRepository.updateCategory(
                        categoryId,
                        request.name() == null ? null : request.name().trim(),
                        request.sortOrder()
                ),
                "Категория с таким названием в этом ресторане уже есть"
        );

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Категорию с id=" + categoryId + " нельзя изменить: она архивирована"
            );
        }

        return MenuResponseMapper.toManagedResponse(requireCategory(categoryId));
    }

    /**
     * Убирает категорию из меню вместе со всеми её блюдами.
     *
     * <p>Именно архивирование, а не DELETE. На {@code menu_item} ссылаются позиции заказов
     * с ON DELETE RESTRICT, поэтому удалить категорию, из которой хоть раз что-то заказали,
     * база просто не даст. И это правильно: заказ обязан помнить, что купил клиент.
     */
    @Transactional
    public void archiveCategory(Long restaurantId, Long categoryId) {
        requireCategoryInRestaurant(restaurantId, categoryId);

        int archived = menuRepository.archiveCategory(categoryId);

        if (archived == 0) {
            throw new BusinessRuleViolationException(
                    "Категория с id=" + categoryId + " уже архивирована"
            );
        }

        log.info("Категория меню архивирована: categoryId={}, restaurantId={}", categoryId, restaurantId);
    }

    // --- Позиции ------------------------------------------------------------------------------

    @Transactional
    public ManagedMenuItemResponse createItem(
            Long restaurantId,
            Long categoryId,
            CreateMenuItemRequest request
    ) {
        MenuCategory category = requireCategoryInRestaurant(restaurantId, categoryId);

        if (category.archived()) {
            throw new BusinessRuleViolationException(
                    "Нельзя добавить блюдо в архивированную категорию с id=" + categoryId
            );
        }

        Long itemId = withDuplicateNameGuard(
                () -> menuRepository.insertItem(
                        categoryId,
                        request.name().trim(),
                        request.description(),
                        request.price(),
                        sortOrderOrDefault(request.sortOrder())
                ),
                "Блюдо с названием «" + request.name().trim() + "» в этой категории уже есть"
        );

        log.info("Создано блюдо: menuItemId={}, categoryId={}", itemId, categoryId);

        return MenuResponseMapper.toManagedResponse(requireItem(itemId));
    }

    /**
     * Изменение цены не трогает существующие корзины и заказы: и {@code cart_item},
     * и {@code customer_order_item} хранят собственную копию цены на момент добавления.
     * Клиент заплатит ту цену, которую видел.
     */
    @Transactional
    public ManagedMenuItemResponse updateItem(Long restaurantId, Long itemId, UpdateMenuItemRequest request) {
        requireItemInRestaurant(restaurantId, itemId);

        int updated = withDuplicateNameGuard(
                () -> menuRepository.updateItem(
                        itemId,
                        request.name() == null ? null : request.name().trim(),
                        request.description(),
                        request.price(),
                        request.sortOrder()
                ),
                "Блюдо с таким названием в этой категории уже есть"
        );

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Блюдо с id=" + itemId + " нельзя изменить: оно архивировано"
            );
        }

        return MenuResponseMapper.toManagedResponse(requireItem(itemId));
    }

    /**
     * Временное скрытие: блюдо закончилось и вернётся. В отличие от архивирования обратимо.
     */
    @Transactional
    public ManagedMenuItemResponse setItemAvailability(Long restaurantId, Long itemId, boolean available) {
        requireItemInRestaurant(restaurantId, itemId);

        int updated = menuRepository.setItemAvailability(itemId, available);

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Доступность блюда с id=" + itemId + " нельзя изменить: оно архивировано"
            );
        }

        return MenuResponseMapper.toManagedResponse(requireItem(itemId));
    }

    @Transactional
    public void archiveItem(Long restaurantId, Long itemId) {
        requireItemInRestaurant(restaurantId, itemId);

        int archived = menuRepository.archiveItem(itemId);

        if (archived == 0) {
            throw new BusinessRuleViolationException("Блюдо с id=" + itemId + " уже архивировано");
        }

        log.info("Блюдо архивировано: menuItemId={}, restaurantId={}", itemId, restaurantId);
    }

    // --- Вспомогательное ----------------------------------------------------------------------

    private MenuCategory requireCategoryInRestaurant(Long restaurantId, Long categoryId) {
        // Проверка принадлежности идёт до всего остального: без неё владелец одного ресторана
        // правил бы меню другого, зная только идентификатор категории.
        if (!menuRepository.categoryBelongsToRestaurant(restaurantId, categoryId)) {
            throw new ResourceNotFoundException(
                    "Категория с id=" + categoryId + " не найдена в ресторане с id=" + restaurantId
            );
        }

        return requireCategory(categoryId);
    }

    private void requireItemInRestaurant(Long restaurantId, Long itemId) {
        if (!menuRepository.itemBelongsToRestaurant(restaurantId, itemId)) {
            throw new ResourceNotFoundException(
                    "Блюдо с id=" + itemId + " не найдено в ресторане с id=" + restaurantId
            );
        }
    }

    private MenuCategory requireCategory(Long categoryId) {
        return menuRepository.findCategoryById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Категория с id=" + categoryId + " не найдена"));
    }

    private MenuItem requireItem(Long itemId) {
        return menuRepository.findItemById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Блюдо с id=" + itemId + " не найдено"));
    }

    /**
     * Уникальность имени проверяет база частичным индексом, а не предварительный SELECT:
     * он не спас бы от двух одновременных запросов с одним названием.
     */
    private <T> T withDuplicateNameGuard(java.util.function.Supplier<T> action, String message) {
        try {
            return action.get();
        } catch (DuplicateKeyException exception) {
            throw new BusinessRuleViolationException(message);
        }
    }

    private int sortOrderOrDefault(Integer sortOrder) {
        return sortOrder == null ? DEFAULT_SORT_ORDER : sortOrder;
    }
}
