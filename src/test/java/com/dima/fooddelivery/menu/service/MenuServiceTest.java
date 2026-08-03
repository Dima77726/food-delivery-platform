package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.api.RestaurantMenuResponse;
import com.dima.fooddelivery.menu.persistence.MenuRow;
import com.dima.fooddelivery.menu.persistence.MenuRowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MenuRowMapper menuRowMapper;

    private MenuService menuService;

    @BeforeEach
    void setUp() {
        menuService = new MenuService(jdbcTemplate, menuRowMapper);
    }

    @Test
    void shouldGroupMenuItemsByCategoryInQueryOrder() {
        Long restaurantId = 20L;
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(restaurantId))).thenReturn(true);
        when(jdbcTemplate.query(anyString(), eq(menuRowMapper), eq(restaurantId))).thenReturn(List.of(
                new MenuRow(1L, "Pizza", 1, 10L, "Margherita", "Tomato and cheese", new BigDecimal("700.00"), true, 1),
                new MenuRow(1L, "Pizza", 1, 11L, "Pepperoni", "Spicy salami", new BigDecimal("850.00"), false, 2),
                new MenuRow(2L, "Drinks", 2, null, null, null, null, null, null)
        ));

        RestaurantMenuResponse response = menuService.getRestaurantMenu(restaurantId);

        assertEquals(restaurantId, response.restaurantId());
        assertEquals(2, response.categories().size());
        assertEquals("Pizza", response.categories().get(0).name());
        assertEquals(2, response.categories().get(0).items().size());
        assertEquals("Margherita", response.categories().get(0).items().get(0).name());
        assertEquals(false, response.categories().get(0).items().get(1).available());
        assertEquals("Drinks", response.categories().get(1).name());
        assertEquals(0, response.categories().get(1).items().size());
    }

    @Test
    void shouldThrowWhenRestaurantDoesNotExist() {
        Long restaurantId = 99L;
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(restaurantId))).thenReturn(false);

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> menuService.getRestaurantMenu(restaurantId)
        );

        assertTrue(exception.getMessage().contains("id=99"));
        verify(jdbcTemplate, never()).query(anyString(), eq(menuRowMapper), eq(restaurantId));
    }
}
