package com.dima.fooddelivery.menu.api;

import java.util.List;

public record MenuCategoryResponse(
        Long id,
        String name,
        List<MenuItemResponse> items
) {
}
