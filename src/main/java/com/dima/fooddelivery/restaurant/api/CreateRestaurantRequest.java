package com.dima.fooddelivery.restaurant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRestaurantRequest(
        @NotBlank(message = "название обязательно")
        @Size(max = 255, message = "название не длиннее 255 символов")
        String name,

        @Size(max = 1000, message = "описание не длиннее 1000 символов")
        String description,

        @NotBlank(message = "город обязателен")
        @Size(max = 255, message = "город не длиннее 255 символов")
        String city
) {
}
