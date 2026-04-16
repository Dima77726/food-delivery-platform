package com.dima.fooddelivery.menu.api;

import java.math.BigDecimal;

public record MenuItemResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        boolean available
) {
}
