package com.dima.fooddelivery.menu.api;

import java.util.List;

public record ManagedMenuResponse(
        Long restaurantId,
        List<ManagedMenuCategoryResponse> categories
) {
}
