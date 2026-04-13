package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final JdbcTemplate jdbcTemplate;

    private final RestaurantRowMapper restaurantRowMapper;

    public List<RestaurantResponse> getRestaurantAll(){

        String sql = """
                SELECT id,name, description, city, is_active 
                FROM food_app.restaurant
                ORDER BY id
                """;

        List<RestaurantResponse> restaurants = jdbcTemplate.query(sql, restaurantRowMapper);

        log.info("Fetched {} restaurants from database", restaurants.size());
        return restaurants;
    }

    public RestaurantResponse getRestaurantById(Long id){
        log.debug("Searching restaurant by id={}", id);

        String sql = """
                SELECT id,name, description, city, is_active
                FROM food_app.restaurant
                WHERE id = ?
        """;

        List<RestaurantResponse> restaurants  = jdbcTemplate.query(sql, restaurantRowMapper, id);

        if(restaurants.isEmpty()){
            log.error("No restaurant found with id={}", id);

            throw new ResourceNotFoundException("No restaurant found with id={}");
        }

        RestaurantResponse restaurantResponse = restaurants.get(0);

        log.info("Restaurant found in database: id={}, name={}", restaurantResponse.id(), restaurantResponse.name());
        return restaurantResponse;

    }
}
