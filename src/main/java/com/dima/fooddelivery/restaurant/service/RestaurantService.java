package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final JdbcTemplate jdbcTemplate;

    private final RestaurantRowMapper restaurantRowMapper;

    @Transactional(readOnly = true)
    public List<RestaurantResponse> getRestaurantAll(){
        log.info("Начинаем загрузку списка ресторанов");

        String sql = """
                SELECT id,name, description, city, is_active 
                FROM food_app.restaurant
                ORDER BY id
                """;

        List<RestaurantResponse> restaurants = jdbcTemplate.query(sql, restaurantRowMapper);

        log.info(
                "Список ресторанов загружен из базы данных: restaurantsCount={}",
                restaurants.size()
        );

        return restaurants;
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantById(Long id){
        log.info(
                "Начинаем поиск ресторана по id: restaurantId={}",
                id
        );

        String sql = """
                SELECT 
                    id,
                    name, 
                    description, 
                    city, 
                    is_active
                FROM food_app.restaurant
                WHERE id = ?
        """;

        List<RestaurantResponse> restaurants  = jdbcTemplate.query(sql, restaurantRowMapper, id);

        if(restaurants.isEmpty()){
            log.warn(
                    "Ресторан не найден: restaurantId={}",
                    id
            );

            throw new ResourceNotFoundException(
                    "Ресторан с id=" + id + " не найден"
            );
        }

        RestaurantResponse restaurantResponse = restaurants.get(0);

        log.info(
                "Ресторан найден: restaurantId={}, name={}",
                restaurantResponse.id(),
                restaurantResponse.name()
        );
        return restaurantResponse;

    }
}
