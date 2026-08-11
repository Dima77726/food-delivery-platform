package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.restaurant.service.RestaurantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Validated
@RestController
@RequestMapping("/api/v1/restaurants")
@Tag(name = "Restaurant", description = "Витрина ресторанов; доступна без входа")
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping
    public List<RestaurantResponse> getAllRestaurants() {
        log.info("Получен запрос на получение списка ресторанов");

        List<RestaurantResponse> restaurants = restaurantService.getAllRestaurants();

        log.info("Возвращаем список ресторанов: restaurantsCount={}", restaurants.size());
        return restaurants;
    }

    @GetMapping("/{id}")
    public RestaurantResponse getRestaurantById(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long id
    ) {
        log.info("Получен запрос на получение ресторана по id: restaurantId={}", id);

        RestaurantResponse restaurant = restaurantService.getRestaurantById(id);

        log.info(
                "Возвращаем ресторан: restaurantId={}, name={}",
                restaurant.id(),
                restaurant.name()
        );

        return restaurant;
    }
}
