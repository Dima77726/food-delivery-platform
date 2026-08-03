package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RestaurantRowMapper restaurantRowMapper;

    private RestaurantService restaurantService;

    @BeforeEach
    void setUp() {
        restaurantService = new RestaurantService(jdbcTemplate, restaurantRowMapper);
    }

    @Test
    void shouldReturnAllRestaurantsOrderedById() {
        List<RestaurantResponse> restaurants = List.of(
                new RestaurantResponse(1L, "Pizza Place", "Italian food", "Moscow", true),
                new RestaurantResponse(2L, "Burger House", "Burgers", "Moscow", false)
        );
        when(jdbcTemplate.query(anyString(), eq(restaurantRowMapper))).thenReturn(restaurants);

        List<RestaurantResponse> response = restaurantService.getRestaurantAll();

        assertEquals(restaurants, response);
        verify(jdbcTemplate).query(anyString(), eq(restaurantRowMapper));
    }

    @Test
    void shouldReturnRestaurantById() {
        RestaurantResponse restaurant = new RestaurantResponse(1L, "Pizza Place", "Italian food", "Moscow", true);
        when(jdbcTemplate.query(anyString(), eq(restaurantRowMapper), eq(1L))).thenReturn(List.of(restaurant));

        RestaurantResponse response = restaurantService.getRestaurantById(1L);

        assertEquals(restaurant, response);
        verify(jdbcTemplate).query(anyString(), eq(restaurantRowMapper), eq(1L));
    }

    @Test
    void shouldThrowWhenRestaurantByIdDoesNotExist() {
        when(jdbcTemplate.query(anyString(), eq(restaurantRowMapper), eq(99L))).thenReturn(List.of());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> restaurantService.getRestaurantById(99L)
        );

        assertTrue(exception.getMessage().contains("id=99"));
    }
}
