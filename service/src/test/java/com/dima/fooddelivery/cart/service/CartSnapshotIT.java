package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Корзина хранит снимок блюда — цену и название на момент добавления.
 *
 * <p>Раньше корзина брала название join'ом с меню, и переименование блюда задним числом
 * меняло корзину. Теперь меню живёт в каталоге, и снимок — единственный способ показать
 * корзину без вызова каталога на каждое чтение. А по смыслу он и правильнее: клиент
 * платит за то, что видел, когда выбирал.
 *
 * <p>Изменение меню здесь делается в обход каталога, прямо в таблице, из которой отвечает
 * тестовый каталог (см. {@code LegacyTablesCatalogClient}): для корзины это ровно то же,
 * что правка меню в настоящем каталоге.
 */
class CartSnapshotIT extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldKeepPriceAndNameFromMomentOfAdding() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("500.00"));
        String originalName = jdbcTemplate.queryForObject("SELECT name FROM menu_item WHERE id = ?", String.class, itemId);

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(itemId, 2));

        jdbcTemplate.update(
                "UPDATE menu_item SET price = 700.00, name = 'Переименованное блюдо' WHERE id = ?",
                itemId
        );

        CartResponse cart = cartService.getActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(new BigDecimal("500.00"), cart.items().get(0).price()),
                () -> assertEquals(originalName, cart.items().get(0).menuItemName()),
                () -> assertEquals(new BigDecimal("1000.00"), cart.totalAmount())
        );
    }
}
