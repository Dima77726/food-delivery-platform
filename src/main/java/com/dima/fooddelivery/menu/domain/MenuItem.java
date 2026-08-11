package com.dima.fooddelivery.menu.domain;

import java.math.BigDecimal;

public record MenuItem(
        Long id,
        String name,
        String description,
        BigDecimal price,
        boolean available
) {
}
