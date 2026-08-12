package com.dima.fooddelivery.restaurant.api;

import jakarta.validation.constraints.Size;

/**
 * Частичное обновление: незаполненные поля не меняются.
 *
 * <p>Отдельный тип от CreateRestaurantRequest, хотя поля те же. Слить их в один — значит
 * потерять @NotBlank при создании либо потребовать все поля при правке одного.
 */
public record UpdateRestaurantRequest(
        @Size(max = 255, message = "название не длиннее 255 символов")
        String name,

        @Size(max = 1000, message = "описание не длиннее 1000 символов")
        String description,

        @Size(max = 255, message = "город не длиннее 255 символов")
        String city
) {
}
