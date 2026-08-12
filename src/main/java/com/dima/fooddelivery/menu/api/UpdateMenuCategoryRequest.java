package com.dima.fooddelivery.menu.api;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateMenuCategoryRequest(
        @Size(max = 255, message = "название не длиннее 255 символов")
        String name,

        @PositiveOrZero(message = "порядок сортировки не может быть отрицательным")
        Integer sortOrder
) {
}
