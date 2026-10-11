package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.domain.MenuCategoryView;
import com.dima.fooddelivery.menu.domain.MenuItemView;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuManagementIT extends AbstractIntegrationTest {

    @Autowired
    private MenuService menuService;

    @Test
    void shouldCreateCategoryWithItemAndShowItInPublicMenu() {
        Long restaurantId = testData.insertRestaurant();

        MenuCategoryView category = menuService.createCategory(restaurantId, "Пицца", 1);
        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Маргарита", "Классика", new BigDecimal("450.00"), 1
        );

        RestaurantMenu publicMenu = menuService.getRestaurantMenu(restaurantId);

        assertAll(
                () -> assertTrue(item.available(), "новое блюдо сразу в продаже"),
                () -> assertFalse(item.archived()),
                () -> assertEquals(1, publicMenu.categories().size()),
                () -> assertEquals(1, publicMenu.categories().get(0).items().size()),
                () -> assertEquals("Маргарита", publicMenu.categories().get(0).items().get(0).name())
        );
    }

    @Test
    void shouldRejectDuplicateCategoryName() {
        Long restaurantId = testData.insertRestaurant();

        menuService.createCategory(restaurantId, "Напитки", 1);

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.createCategory(restaurantId, "Напитки", 2)
        );
    }

    /**
     * Ради этого правила и делалось архивирование: имя освобождается вместе с уходом позиции
     * из меню, иначе завести блюдо с прежним названием было бы невозможно навсегда.
     */
    @Test
    void shouldAllowReusingNameAfterArchiving() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Пицца", 1);

        MenuItemView first = menuService.createItem(
                restaurantId, category.id(), "Пепперони", null, new BigDecimal("520.00"), 1
        );

        menuService.archiveItem(restaurantId, first.id());

        MenuItemView second = menuService.createItem(
                restaurantId, category.id(), "Пепперони", "Новый рецепт", new BigDecimal("560.00"), 1
        );

        assertAll(
                () -> assertFalse(second.id().equals(first.id()), "должна появиться новая позиция"),
                () -> assertEquals(0, new BigDecimal("560.00").compareTo(second.price()))
        );
    }

    @Test
    void shouldHideArchivedItemFromPublicMenuButKeepItForOwner() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Десерты", 1);

        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Тирамису", null, new BigDecimal("260.00"), 1
        );

        menuService.archiveItem(restaurantId, item.id());

        RestaurantMenu publicMenu = menuService.getRestaurantMenu(restaurantId);
        RestaurantMenu managedMenu = menuService.getManagedMenu(restaurantId);

        assertAll(
                () -> assertTrue(
                        publicMenu.categories().get(0).items().isEmpty(),
                        "посетитель архивную позицию видеть не должен"
                ),
                () -> assertEquals(
                        1,
                        managedMenu.categories().get(0).items().size(),
                        "владелец обязан видеть то, что сам убрал"
                ),
                () -> assertTrue(managedMenu.categories().get(0).items().get(0).archived())
        );
    }

    /**
     * Ровно на этот метод опирается gRPC-ответ корзине: архивное блюдо — «нет такого»,
     * и в корзину его не положить.
     */
    @Test
    void shouldNotAllowOrderingArchivedItem() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Супы", 1);

        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Борщ", null, new BigDecimal("300.00"), 1
        );

        menuService.archiveItem(restaurantId, item.id());

        assertThrows(
                ResourceNotFoundException.class,
                () -> menuService.requireAvailableItem(restaurantId, item.id())
        );
    }

    @Test
    void shouldNotAllowOrderingOutOfStockItem() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Салаты", 1);

        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Цезарь", null, new BigDecimal("380.00"), 1
        );

        menuService.setItemAvailability(restaurantId, item.id(), false);

        // Закончившееся блюдо остаётся в меню, но заказать его нельзя: 409, а не 404 —
        // позиция существует, просто временно недоступна.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.requireAvailableItem(restaurantId, item.id())
        );
    }

    @Test
    void shouldArchiveCategoryTogetherWithItsItems() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Закуски", 1);

        menuService.createItem(restaurantId, category.id(), "Брускетта", null, new BigDecimal("240.00"), 1);
        menuService.createItem(restaurantId, category.id(), "Оливки", null, new BigDecimal("180.00"), 2);

        menuService.archiveCategory(restaurantId, category.id());

        RestaurantMenu publicMenu = menuService.getRestaurantMenu(restaurantId);
        RestaurantMenu managedMenu = menuService.getManagedMenu(restaurantId);

        assertAll(
                () -> assertTrue(publicMenu.categories().isEmpty(), "категория пропала из витрины"),
                () -> assertTrue(managedMenu.categories().get(0).archived()),
                () -> assertTrue(
                        managedMenu.categories().get(0).items().stream().allMatch(MenuItemView::archived),
                        "блюда должны уйти в архив вместе с категорией"
                )
        );
    }

    @Test
    void shouldNotAddItemToArchivedCategory() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Гриль", 1);

        menuService.archiveCategory(restaurantId, category.id());

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.createItem(
                        restaurantId, category.id(), "Стейк", null, new BigDecimal("900.00"), 1
                )
        );
    }

    /**
     * Проверка принадлежности живёт в сервисе и не полагается на то, что вызывающий
     * подставит правильный restaurantId.
     */
    @Test
    void shouldNotTouchMenuOfAnotherRestaurant() {
        Long ownRestaurantId = testData.insertRestaurant();
        Long foreignRestaurantId = testData.insertRestaurant();

        MenuCategoryView foreignCategory = menuService.createCategory(foreignRestaurantId, "Чужая категория", 1);

        assertThrows(
                ResourceNotFoundException.class,
                () -> menuService.createItem(
                        ownRestaurantId, foreignCategory.id(), "Взлом", null, new BigDecimal("1.00"), 1
                )
        );
    }

    @Test
    void shouldRejectDoubleArchiving() {
        Long restaurantId = testData.insertRestaurant();
        MenuCategoryView category = menuService.createCategory(restaurantId, "Роллы", 1);

        MenuItemView item = menuService.createItem(
                restaurantId, category.id(), "Филадельфия", null, new BigDecimal("430.00"), 1
        );

        menuService.archiveItem(restaurantId, item.id());

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.archiveItem(restaurantId, item.id())
        );
    }
}
