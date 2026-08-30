package com.dima.fooddelivery.common.cache;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuCategoryView;
import com.dima.fooddelivery.menu.domain.MenuItemView;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.menu.persistence.MenuCategoryRepository;
import com.dima.fooddelivery.menu.service.MenuService;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кэш витрины на настоящем Redis.
 *
 * <p><b>Главное, что выяснилось при написании этих тестов.</b> Запись в кэш и вытеснение
 * из него становятся видны не мгновенно: после возврата из метода с {@code @CacheEvict}
 * следующее чтение ещё может застать старое значение. Первые версии писались в предположении
 * «вернулся метод — значит кэш уже обновлён» и стабильно флейкали: падал то один тест,
 * то другой, в зависимости от того, кто успел первым.
 *
 * <p>Отсюда два вывода. Первый, для тестов: проверять надо наблюдаемое поведение с ожиданием,
 * а не мгновенное состояние. Второй, важнее, для проектирования: кэш даёт согласованность
 * через короткое время, а не сразу. Витрину это устраивает. Любое чтение, по которому
 * принимается решение, кэшировать нельзя — и оно здесь не кэшируется, чему посвящён
 * отдельный тест.
 *
 * <p>Приём, на котором держатся проверки попадания в кэш: изменить данные <em>в обход
 * сервиса</em>, прямо через репозиторий. Сервис вытеснил бы запись, и отличить чтение
 * из кэша от чтения из базы стало бы невозможно.
 */
class MenuCacheIT extends AbstractIntegrationTest {

    private static final Duration CACHE_VISIBILITY_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private MenuService menuService;

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired
    private CacheManager cacheManager;

    /**
     * {@code RedisCacheManager} создаёт объекты кэшей лениво. Обращение к ним заранее
     * убирает из измерения время первой инициализации.
     */
    @BeforeEach
    void warmUpCacheManager() {
        cacheManager.getCacheNames().forEach(cacheManager::getCache);
    }

    private void awaitTrue(BooleanSupplier condition, String description) {
        Instant deadline = Instant.now().plus(CACHE_VISIBILITY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание прервано", interrupted);
            }
        }

        throw new AssertionError("Не дождались за " + CACHE_VISIBILITY_TIMEOUT + ": " + description);
    }

    private void awaitMenuCached(Long restaurantId) {
        awaitTrue(
                () -> {
                    menuService.getRestaurantMenu(restaurantId);
                    return cacheManager.getCache(CacheNames.RESTAURANT_MENU).get(restaurantId) != null;
                },
                "меню ресторана " + restaurantId + " не попало в кэш"
        );
    }

    private void awaitRestaurantListCached() {
        awaitTrue(
                () -> {
                    restaurantService.getAllRestaurants();
                    return cacheManager.getCache(CacheNames.RESTAURANTS).get("all") != null;
                },
                "список ресторанов не попал в кэш"
        );
    }

    private int publicMenuItemCount(Long restaurantId) {
        RestaurantMenu menu = menuService.getRestaurantMenu(restaurantId);

        return menu.categories().isEmpty() ? 0 : menu.categories().get(0).items().size();
    }

    @Test
    void shouldServeSecondMenuReadFromCache() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Пицца", 1);
        menuService.createItem(restaurantId, category.id(), "Маргарита", null, new BigDecimal("450.00"), 1);

        awaitMenuCached(restaurantId);

        // В обход сервиса: кэш об этом изменении не узнает. Работа идёт через репозиторий,
        // то есть блюдо добавляется в сам агрегат и уезжает в базу каскадом от категории.
        MenuCategory managedCategory = menuCategoryRepository.findById(category.id()).orElseThrow();
        managedCategory.addItem("Пепперони", null, new BigDecimal("520.00"), 2);
        menuCategoryRepository.saveAndFlush(managedCategory);

        assertEquals(
                1,
                publicMenuItemCount(restaurantId),
                "чтение обязано прийти из кэша и не увидеть правку в обход сервиса"
        );
    }

    @Test
    void shouldEvictMenuCacheWhenItemAdded() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Пицца", 1);
        menuService.createItem(restaurantId, category.id(), "Маргарита", null, new BigDecimal("450.00"), 1);

        awaitMenuCached(restaurantId);
        assertEquals(1, publicMenuItemCount(restaurantId));

        menuService.createItem(restaurantId, category.id(), "Пепперони", null, new BigDecimal("520.00"), 2);

        awaitTrue(
                () -> publicMenuItemCount(restaurantId) == 2,
                "добавленное блюдо не появилось в витрине"
        );
    }

    @Test
    void shouldEvictMenuCacheWhenItemArchived() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Десерты", 1);
        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Тирамису", null, new BigDecimal("260.00"), 1
        );

        awaitMenuCached(restaurantId);
        assertEquals(1, publicMenuItemCount(restaurantId));

        menuService.archiveItem(restaurantId, item.id());

        awaitTrue(
                () -> publicMenuItemCount(restaurantId) == 0,
                "архивированное блюдо осталось в кэшированной витрине"
        );
    }

    @Test
    void shouldEvictMenuCacheWhenPriceChanged() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Паста", 1);
        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Карбонара", null, new BigDecimal("500.00"), 1
        );

        awaitMenuCached(restaurantId);
        menuService.updateItem(restaurantId, item.id(), null, null, new BigDecimal("700.00"), null);

        awaitTrue(
                () -> new BigDecimal("700.00").compareTo(
                        menuService.getRestaurantMenu(restaurantId).categories().get(0).items().get(0).price()
                ) == 0,
                "витрина не показала новую цену"
        );
    }

    /**
     * Здесь никакого ожидания нет и быть не может: закрытый ресторан обязан перестать
     * принимать заказы немедленно, а не «в течение нескольких миллисекунд». Ровно поэтому
     * это чтение и не кэшируется.
     */
    @Test
    void shouldNotCacheRestaurantUsedForOrderingDecision() {
        Long restaurantId = testData.insertRestaurant();

        restaurantService.requireActiveRestaurant(restaurantId);
        restaurantService.setActive(restaurantId, false);

        BusinessRuleViolationException error = assertThrows(
                BusinessRuleViolationException.class,
                () -> restaurantService.requireActiveRestaurant(restaurantId)
        );

        assertTrue(error.getMessage().contains("недоступен"));
    }

    @Test
    void shouldCacheRestaurantListAndEvictItOnChange() {
        Long restaurantId = testData.insertRestaurant();

        awaitRestaurantListCached();
        int before = restaurantService.getAllRestaurants().size();

        // В обход сервиса — кэш об этом не узнает.
        testData.insertRestaurant();

        assertEquals(
                before,
                restaurantService.getAllRestaurants().size(),
                "список витрины обязан прийти из кэша"
        );

        // А это уже через сервис: срабатывает вытеснение, и второй ресторан становится виден.
        restaurantService.setActive(restaurantId, false);

        awaitTrue(
                () -> restaurantService.getAllRestaurants().size() == before + 1,
                "после вытеснения список должен читаться заново"
        );
    }

    /**
     * Доменные типы кладутся в Redis как JSON и обязаны читаться обратно без потерь.
     * Особенно цена: BigDecimal, проехавший через double, вернулся бы с копеечным хвостом.
     */
    @Test
    void shouldRoundTripDomainTypesThroughRedisWithoutLosingPrecision() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Паста", 1);
        menuService.createItem(restaurantId, category.id(), "Карбонара", "Описание", new BigDecimal("499.99"), 1);

        awaitMenuCached(restaurantId);

        RestaurantMenu fromCache = menuService.getRestaurantMenu(restaurantId);
        MenuItemView item = fromCache.categories().get(0).items().get(0);

        assertAll(
                () -> assertEquals(0, new BigDecimal("499.99").compareTo(item.price())),
                () -> assertEquals("Карбонара", item.name()),
                () -> assertEquals("Описание", item.description()),
                () -> assertTrue(item.available()),
                () -> assertEquals(restaurantId, fromCache.restaurantId())
        );
    }
}
