package com.dima.fooddelivery.menu.domain;

import java.util.List;

public record RestaurantMenu(
        Long restaurantId,
        List<MenuCategory> categories
) {
}
