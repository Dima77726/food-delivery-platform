package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.api.CreateMenuCategoryRequest;
import com.dima.fooddelivery.menu.api.CreateMenuItemRequest;
import com.dima.fooddelivery.menu.api.ManagedMenuCategoryResponse;
import com.dima.fooddelivery.menu.api.ManagedMenuItemResponse;
import com.dima.fooddelivery.menu.api.RestaurantMenuResponse;
import com.dima.fooddelivery.menu.api.UpdateMenuItemRequest;
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

    @Autowired
    private CartService cartService;

    @Test
    void shouldCreateCategoryWithItemAndShowItInPublicMenu() {
        Long restaurantId = testData.insertRestaurant();

        ManagedMenuCategoryResponse category = menuService.createCategory(
                restaurantId,
                new CreateMenuCategoryRequest("Пицца", 1)
        );

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId,
                category.id(),
                new CreateMenuItemRequest("Маргарита", "Классика", new BigDecimal("450.00"), 1)
        );

        RestaurantMenuResponse publicMenu = menuService.getRestaurantMenu(restaurantId);

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

        menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Напитки", 1));

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Напитки", 2))
        );
    }

    /**
     * Ради этого правила и делалось архивирование: имя освобождается вместе с уходом позиции
     * из меню, иначе завести блюдо с прежним названием было бы невозможно навсегда.
     */
    @Test
    void shouldAllowReusingNameAfterArchiving() {
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Пицца", 1));

        ManagedMenuItemResponse first = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Пепперони", null, new BigDecimal("520.00"), 1)
        );

        menuService.archiveItem(restaurantId, first.id());

        ManagedMenuItemResponse second = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Пепперони", "Новый рецепт", new BigDecimal("560.00"), 1)
        );

        assertAll(
                () -> assertFalse(second.id().equals(first.id()), "должна появиться новая позиция"),
                () -> assertEquals(new BigDecimal("560.00"), second.price())
        );
    }

    @Test
    void shouldHideArchivedItemFromPublicMenuButKeepItForOwner() {
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Десерты", 1));

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Тирамису", null, new BigDecimal("260.00"), 1)
        );

        menuService.archiveItem(restaurantId, item.id());

        RestaurantMenuResponse publicMenu = menuService.getRestaurantMenu(restaurantId);
        var managedMenu = menuService.getManagedMenu(restaurantId);

        assertAll(
                () -> assertTrue(
                        publicMenu.categories().get(0).items().isEmpty(),
                        "клиент архивную позицию видеть не должен"
                ),
                () -> assertEquals(
                        1,
                        managedMenu.categories().get(0).items().size(),
                        "владелец обязан видеть то, что сам убрал"
                ),
                () -> assertTrue(managedMenu.categories().get(0).items().get(0).archived())
        );
    }

    @Test
    void shouldNotAllowOrderingArchivedItem() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Супы", 1));

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Борщ", null, new BigDecimal("300.00"), 1)
        );

        menuService.archiveItem(restaurantId, item.id());

        assertThrows(
                ResourceNotFoundException.class,
                () -> cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(item.id(), 1))
        );
    }

    @Test
    void shouldNotAllowOrderingOutOfStockItem() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Салаты", 1));

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Цезарь", null, new BigDecimal("380.00"), 1)
        );

        menuService.setItemAvailability(restaurantId, item.id(), false);

        // Закончившееся блюдо остаётся в меню, но заказать его нельзя: 409, а не 404 —
        // позиция существует, просто временно недоступна.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(item.id(), 1))
        );
    }

    /**
     * Цена в корзине зафиксирована на момент добавления. Иначе подорожание меню меняло бы
     * сумму в уже собранной корзине, и клиент платил бы не то, что видел.
     */
    @Test
    void shouldNotChangePriceInExistingCartWhenMenuPriceChanges() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Паста", 1));

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Карбонара", null, new BigDecimal("500.00"), 1)
        );

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(item.id(), 2));

        menuService.updateItem(
                restaurantId,
                item.id(),
                new UpdateMenuItemRequest(null, null, new BigDecimal("700.00"), null)
        );

        CartResponse cart = cartService.getActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(new BigDecimal("500.00"), cart.items().get(0).price()),
                () -> assertEquals(new BigDecimal("1000.00"), cart.totalAmount())
        );
    }

    @Test
    void shouldArchiveCategoryTogetherWithItsItems() {
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Закуски", 1));

        menuService.createItem(restaurantId, category.id(),
                new CreateMenuItemRequest("Брускетта", null, new BigDecimal("240.00"), 1));
        menuService.createItem(restaurantId, category.id(),
                new CreateMenuItemRequest("Оливки", null, new BigDecimal("180.00"), 2));

        menuService.archiveCategory(restaurantId, category.id());

        RestaurantMenuResponse publicMenu = menuService.getRestaurantMenu(restaurantId);
        var managedMenu = menuService.getManagedMenu(restaurantId);

        assertAll(
                () -> assertTrue(publicMenu.categories().isEmpty(), "категория пропала из витрины"),
                () -> assertTrue(managedMenu.categories().get(0).archived()),
                () -> assertTrue(
                        managedMenu.categories().get(0).items().stream()
                                .allMatch(ManagedMenuItemResponse::archived),
                        "блюда должны уйти в архив вместе с категорией"
                )
        );
    }

    @Test
    void shouldNotAddItemToArchivedCategory() {
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Гриль", 1));

        menuService.archiveCategory(restaurantId, category.id());

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.createItem(restaurantId, category.id(),
                        new CreateMenuItemRequest("Стейк", null, new BigDecimal("900.00"), 1))
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

        ManagedMenuCategoryResponse foreignCategory = menuService.createCategory(
                foreignRestaurantId,
                new CreateMenuCategoryRequest("Чужая категория", 1)
        );

        assertThrows(
                ResourceNotFoundException.class,
                () -> menuService.createItem(ownRestaurantId, foreignCategory.id(),
                        new CreateMenuItemRequest("Взлом", null, new BigDecimal("1.00"), 1))
        );
    }

    @Test
    void shouldRejectDoubleArchiving() {
        Long restaurantId = testData.insertRestaurant();
        ManagedMenuCategoryResponse category =
                menuService.createCategory(restaurantId, new CreateMenuCategoryRequest("Роллы", 1));

        ManagedMenuItemResponse item = menuService.createItem(
                restaurantId, category.id(),
                new CreateMenuItemRequest("Филадельфия", null, new BigDecimal("430.00"), 1)
        );

        menuService.archiveItem(restaurantId, item.id());

        assertThrows(
                BusinessRuleViolationException.class,
                () -> menuService.archiveItem(restaurantId, item.id())
        );
    }
}
