package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;

public final class MenuResponseMapper {

    public static RestaurantMenuResponse toResponse(RestaurantMenu menu) {
        return new RestaurantMenuResponse(
                menu.restaurantId(),
                menu.categories().stream().map(MenuResponseMapper::toResponse).toList()
        );
    }

    private static MenuCategoryResponse toResponse(MenuCategory category) {
        return new MenuCategoryResponse(
                category.id(),
                category.name(),
                category.items().stream().map(MenuResponseMapper::toResponse).toList()
        );
    }

    private static MenuItemResponse toResponse(MenuItem item) {
        return new MenuItemResponse(
                item.id(),
                item.name(),
                item.description(),
                item.price(),
                item.available()
        );
    }

    private MenuResponseMapper() {
    }
}
