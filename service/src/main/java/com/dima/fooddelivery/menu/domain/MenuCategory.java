package com.dima.fooddelivery.menu.domain;

import java.util.List;

public record MenuCategory(
        Long id,
        String name,
        int sortOrder,
        boolean archived,
        List<MenuItem> items
) {
}
