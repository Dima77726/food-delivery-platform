package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.generated.api.MenuCatalogApi;
import com.dima.fooddelivery.generated.model.RestaurantMenu;
import com.dima.fooddelivery.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Витрина меню. Открыта без аутентификации — ради неё посетитель и приходит.
 */
@RestController
@RequiredArgsConstructor
public class MenuController implements MenuCatalogApi {

    private final MenuService menuService;

    @Override
    public ResponseEntity<RestaurantMenu> getRestaurantMenu(Long restaurantId) {
        return ResponseEntity.ok(MenuResponseMapper.toResponse(menuService.getRestaurantMenu(restaurantId)));
    }
}
