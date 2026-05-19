package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.menu.service.MenuService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/api/v1/restaurants/{restaurantId}/menu")
    public RestaurantMenuResponse getRestaurantMenu(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ){
        log.info("Получен запрос на получение меню ресторана: restaurantId={}", restaurantId);

        RestaurantMenuResponse response = menuService.getRestaurantMenu(restaurantId);
        log.info(
                "Возвращаем меню ресторана: restaurantId={}, categoriesCount={}",
                restaurantId,
                response.categories().size()
        );
        return response;
    }
}
