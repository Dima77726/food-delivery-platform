package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.api.UpdateCartItemQuantityRequest;
import com.dima.fooddelivery.cart.persistence.CartRow;
import com.dima.fooddelivery.cart.persistence.CartRowMapper;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CartRowMapper cartRowMapper;

    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(jdbcTemplate, cartRowMapper);
    }

    @Test
    void shouldReturnActiveCartWithCalculatedTotalsWhenCartContainsItems() {
        Long customerId = 10L;
        Long restaurantId = 20L;

        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                cartRow(100L, customerId, restaurantId, 1001L, 501L, "Pizza Margherita", 2, "700.00"),
                cartRow(100L, customerId, restaurantId, 1002L, 502L, "Burger", 3, "450.50")
        ));

        CartResponse response = cartService.getActiveCart(customerId, restaurantId);

        assertEquals(100L, response.id());
        assertEquals(customerId, response.customerId());
        assertEquals(restaurantId, response.restaurantId());
        assertEquals("ACTIVE", response.status());
        assertEquals(2, response.items().size());
        assertEquals(new BigDecimal("1400.00"), response.items().get(0).lineTotal());
        assertEquals(new BigDecimal("1351.50"), response.items().get(1).lineTotal());
        assertEquals(new BigDecimal("2751.50"), response.totalAmount());

        verifyActiveCartLoaded(customerId, restaurantId);
    }

    @Test
    void shouldReturnActiveCartWithEmptyItemsWhenCartDoesNotContainItems() {
        Long customerId = 10L;
        Long restaurantId = 20L;

        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                new CartRow(100L, customerId, restaurantId, "ACTIVE", null, null, null, null, null)
        ));

        CartResponse response = cartService.getActiveCart(customerId, restaurantId);

        assertEquals(100L, response.id());
        assertEquals(customerId, response.customerId());
        assertEquals(restaurantId, response.restaurantId());
        assertEquals("ACTIVE", response.status());
        assertEquals(0, response.items().size());
        assertEquals(BigDecimal.ZERO, response.totalAmount());

        verifyActiveCartLoaded(customerId, restaurantId);
    }

    @Test
    void shouldThrowResourceNotFoundExceptionWhenActiveCartDoesNotExist() {
        Long customerId = 10L;
        Long restaurantId = 20L;

        mockActiveCartResponseRows(customerId, restaurantId, List.of());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> cartService.getActiveCart(customerId, restaurantId)
        );

        assertTrue(exception.getMessage().contains("customerId=10"));
        assertTrue(exception.getMessage().contains("restaurantId=20"));
        verifyActiveCartLoaded(customerId, restaurantId);
    }

    @Test
    void shouldAddItemToExistingActiveCartAndReturnUpdatedCart() {
        Long customerId = 10L;
        Long restaurantId = 20L;
        Long cartId = 100L;
        Long menuItemId = 501L;
        Integer quantity = 2;
        BigDecimal price = new BigDecimal("700.00");

        mockActiveRestaurant(restaurantId);
        mockAvailableMenuItem(restaurantId, menuItemId, price);
        mockExistingActiveCartId(customerId, restaurantId, cartId);
        mockSuccessfulCartItemUpsert(cartId, menuItemId, quantity, price);
        mockSuccessfulCartTouch(cartId);
        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                cartRow(cartId, customerId, restaurantId, 1001L, menuItemId, "Pizza Margherita", quantity, "700.00")
        ));

        CartResponse response = cartService.addItemToCart(
                customerId,
                restaurantId,
                new AddCartItemRequest(menuItemId, quantity)
        );

        assertEquals(cartId, response.id());
        assertEquals(1, response.items().size());
        assertEquals(new BigDecimal("1400.00"), response.totalAmount());
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), eq(customerId), eq(restaurantId));
    }

    @Test
    void shouldCreateCartWhenAddingItemWithoutExistingActiveCart() {
        Long customerId = 10L;
        Long restaurantId = 20L;
        Long cartId = 100L;
        Long menuItemId = 501L;
        Integer quantity = 1;
        BigDecimal price = new BigDecimal("300.00");

        mockActiveRestaurant(restaurantId);
        mockAvailableMenuItem(restaurantId, menuItemId, price);
        mockExistingActiveCartId(customerId, restaurantId, null);
        when(jdbcTemplate.queryForObject(sqlContaining("INSERT INTO food_app.cart"), eq(Long.class), eq(customerId), eq(restaurantId)))
                .thenReturn(cartId);
        mockSuccessfulCartItemUpsert(cartId, menuItemId, quantity, price);
        mockSuccessfulCartTouch(cartId);
        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                cartRow(cartId, customerId, restaurantId, 1001L, menuItemId, "Soup", quantity, "300.00")
        ));

        CartResponse response = cartService.addItemToCart(
                customerId,
                restaurantId,
                new AddCartItemRequest(menuItemId, quantity)
        );

        assertEquals(cartId, response.id());
        assertEquals(new BigDecimal("300.00"), response.totalAmount());
        verify(jdbcTemplate).queryForObject(sqlContaining("INSERT INTO food_app.cart"), eq(Long.class), eq(customerId), eq(restaurantId));
    }

    @Test
    void shouldRejectUnavailableMenuItem() {
        Long restaurantId = 20L;
        Long menuItemId = 501L;

        mockActiveRestaurant(restaurantId);
        mockMenuItemRows(restaurantId, menuItemId, List.of(new MenuItemRow(menuItemId, new BigDecimal("700.00"), false)));

        assertThrows(
                BusinessRuleViolationException.class,
                () -> cartService.addItemToCart(10L, restaurantId, new AddCartItemRequest(menuItemId, 1))
        );

        verify(jdbcTemplate, never()).update(anyString(), ArgumentMatchers.<Object>any());
    }

    @Test
    void shouldUpdateCartItemQuantityAndReturnUpdatedCart() {
        Long customerId = 10L;
        Long restaurantId = 20L;
        Long cartId = 100L;
        Long cartItemId = 1001L;

        mockActiveRestaurant(restaurantId);
        mockExistingActiveCartId(customerId, restaurantId, cartId);
        when(jdbcTemplate.update(sqlContaining("SET quantity = ?"), eq(4), eq(cartItemId), eq(cartId)))
                .thenReturn(1);
        mockSuccessfulCartTouch(cartId);
        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                cartRow(cartId, customerId, restaurantId, cartItemId, 501L, "Pizza Margherita", 4, "700.00")
        ));

        CartResponse response = cartService.updateCartItemQuantity(
                customerId,
                restaurantId,
                cartItemId,
                new UpdateCartItemQuantityRequest(4)
        );

        assertEquals(new BigDecimal("2800.00"), response.totalAmount());
        assertEquals(4, response.items().get(0).quantity());
    }

    @Test
    void shouldThrowWhenUpdatingMissingCartItem() {
        Long customerId = 10L;
        Long restaurantId = 20L;
        Long cartId = 100L;
        Long cartItemId = 999L;

        mockActiveRestaurant(restaurantId);
        mockExistingActiveCartId(customerId, restaurantId, cartId);
        when(jdbcTemplate.update(sqlContaining("SET quantity = ?"), eq(4), eq(cartItemId), eq(cartId)))
                .thenReturn(0);

        assertThrows(
                ResourceNotFoundException.class,
                () -> cartService.updateCartItemQuantity(customerId, restaurantId, cartItemId, new UpdateCartItemQuantityRequest(4))
        );

        verify(jdbcTemplate, never()).update(sqlContaining("UPDATE food_app.cart SET updated_at"), eq(cartId));
    }

    @Test
    void shouldRemoveCartItemAndReturnUpdatedCart() {
        Long customerId = 10L;
        Long restaurantId = 20L;
        Long cartId = 100L;
        Long cartItemId = 1001L;

        mockActiveRestaurant(restaurantId);
        mockExistingActiveCartId(customerId, restaurantId, cartId);
        when(jdbcTemplate.update(sqlContaining("DELETE FROM food_app.cart_item"), eq(cartItemId), eq(cartId)))
                .thenReturn(1);
        mockSuccessfulCartTouch(cartId);
        mockActiveCartResponseRows(customerId, restaurantId, List.of(
                new CartRow(cartId, customerId, restaurantId, "ACTIVE", null, null, null, null, null)
        ));

        CartResponse response = cartService.removeCartItem(customerId, restaurantId, cartItemId);

        assertEquals(0, response.items().size());
        assertEquals(BigDecimal.ZERO, response.totalAmount());
    }

    private void mockActiveRestaurant(Long restaurantId) {
        when(jdbcTemplate.query(
                sqlContaining("select r.is_active"),
                ArgumentMatchers.<RowMapper<Boolean>>any(),
                eq(restaurantId)
        )).thenReturn(List.of(true));
    }

    private void mockAvailableMenuItem(Long restaurantId, Long menuItemId, BigDecimal price) {
        mockMenuItemRows(restaurantId, menuItemId, List.of(new MenuItemRow(menuItemId, price, true)));
    }

    private void mockMenuItemRows(Long restaurantId, Long menuItemId, List<MenuItemRow> rows) {
        when(jdbcTemplate.query(
                sqlContaining("mi.is_available"),
                ArgumentMatchers.<RowMapper<MenuItemRow>>any(),
                eq(restaurantId),
                eq(menuItemId)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            List<Object> mappedRows = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                MenuItemRow row = rows.get(i);
                ResultSet resultSet = mock(ResultSet.class);
                when(resultSet.getLong("id")).thenReturn(row.id());
                when(resultSet.getBigDecimal("price")).thenReturn(row.price());
                when(resultSet.getBoolean("is_available")).thenReturn(row.isAvailable());
                mappedRows.add(mapper.mapRow(resultSet, i));
            }

            return mappedRows;
        });
    }

    private void mockExistingActiveCartId(Long customerId, Long restaurantId, Long cartId) {
        List<Long> ids = cartId == null ? List.of() : List.of(cartId);
        when(jdbcTemplate.query(
                sqlContaining("SELECT c.id FROM food_app.cart c"),
                ArgumentMatchers.<RowMapper<Long>>any(),
                eq(customerId),
                eq(restaurantId)
        )).thenReturn(ids);
    }

    private void mockSuccessfulCartItemUpsert(Long cartId, Long menuItemId, Integer quantity, BigDecimal price) {
        when(jdbcTemplate.update(
                sqlContaining("ON CONFLICT (cart_id, menu_item_id)"),
                eq(cartId),
                eq(menuItemId),
                eq(quantity),
                eq(price)
        )).thenReturn(1);
    }

    private void mockSuccessfulCartTouch(Long cartId) {
        when(jdbcTemplate.update(sqlContaining("UPDATE food_app.cart SET updated_at"), eq(cartId))).thenReturn(1);
    }

    private void mockActiveCartResponseRows(Long customerId, Long restaurantId, List<CartRow> rows) {
        when(jdbcTemplate.query(
                sqlContaining("left join food_app.cart_item"),
                eq(cartRowMapper),
                eq(customerId),
                eq(restaurantId)
        )).thenReturn(rows);
    }

    private void verifyActiveCartLoaded(Long customerId, Long restaurantId) {
        verify(jdbcTemplate).query(
                sqlContaining("left join food_app.cart_item"),
                eq(cartRowMapper),
                eq(customerId),
                eq(restaurantId)
        );
    }

    private CartRow cartRow(
            Long cartId,
            Long customerId,
            Long restaurantId,
            Long cartItemId,
            Long menuItemId,
            String menuItemName,
            Integer quantity,
            String price
    ) {
        return new CartRow(
                cartId,
                customerId,
                restaurantId,
                "ACTIVE",
                cartItemId,
                menuItemId,
                menuItemName,
                quantity,
                new BigDecimal(price)
        );
    }

    private String sqlContaining(String expected) {
        return argThat(sql -> sql != null && normalizeSql(sql).contains(normalizeSql(expected)));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private record MenuItemRow(Long id, BigDecimal price, Boolean isAvailable) {
    }
}
