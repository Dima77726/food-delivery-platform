package com.dima.fooddelivery.menu.api;

import java.util.List;

public record RestaurantMenuResponse(
        Long restaurantId,
        List<MenuCategoryResponse> categories
) {
}
