package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.restaurant.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping
    public List<RestaurantResponse> getAllRestaurants() {
        log.info("Getting all restaurants");

        List<RestaurantResponse> restaurants = restaurantService.getRestaurantAll();

        log.info("Returning {} restaurants", restaurants.size());
        return restaurants;
    }

    @GetMapping("/{id}")
    public RestaurantResponse getRestaurantById(@PathVariable Long id) {
        log.info("Getting restaurant by id {}", id);

        RestaurantResponse restaurant = restaurantService.getRestaurantById(id);

        log.info("Restaurant found: id = {}, name = {}", restaurant.id(), restaurant.name());
        return restaurant;
    }
}
