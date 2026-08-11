package com.dima.fooddelivery.restaurant.domain;

public record Restaurant(
        Long id,
        String name,
        String description,
        String city,
        boolean active
) {
}
