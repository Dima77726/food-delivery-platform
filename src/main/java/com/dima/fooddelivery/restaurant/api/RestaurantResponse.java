package com.dima.fooddelivery.restaurant.api;

public record RestaurantResponse(
        Long id,
        String name,
        String description,
        String city,
        boolean active
) {
}
