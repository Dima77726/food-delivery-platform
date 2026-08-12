package com.dima.fooddelivery.menu.api;

import java.util.List;

public record ManagedMenuCategoryResponse(
        Long id,
        String name,
        int sortOrder,
        boolean archived,
        List<ManagedMenuItemResponse> items
) {
}
