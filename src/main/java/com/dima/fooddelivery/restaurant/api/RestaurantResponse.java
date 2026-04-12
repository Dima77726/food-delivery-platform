package com.dima.fooddelivery.restaurant.api;

public record RestaurantResponse(
        Long id,
        String name,
        String city,
        boolean active
) {
}
