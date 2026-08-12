package com.dima.fooddelivery.menu.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateMenuItemRequest(
        @NotBlank(message = "название блюда обязательно")
        @Size(max = 255, message = "название не длиннее 255 символов")
        String name,

        @Size(max = 100, message = "описание не длиннее 100 символов")
        String description,

        @NotNull(message = "цена обязательна")
        @PositiveOrZero(message = "цена не может быть отрицательной")
        // Колонка numeric(10,2): большее число дробных знаков база молча округлила бы,
        // и цена в ответе отличалась бы от присланной.
        @Digits(integer = 8, fraction = 2, message = "цена: не больше 8 целых и 2 дробных знаков")
        BigDecimal price,

        @PositiveOrZero(message = "порядок сортировки не может быть отрицательным")
        Integer sortOrder
) {
}
