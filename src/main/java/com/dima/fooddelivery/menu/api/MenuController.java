package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/api/v1/restaurants/{restaurantId}/menu")
    public RestaurantMenuResponse getRestaurantMenu(@PathVariable Long restaurantId){
        log.info("Received request to fetch menu for restaurantId={}", restaurantId);

        RestaurantMenuResponse response = menuService.getRestaurantMenu(restaurantId);
        log.info(
                "Returning menu for restaurantId={}, categoriesCount={}",
                restaurantId,
                response.categories().size()
        );
        return response;
    }
}
