package com.dima.fooddelivery.menu.persistence;

import java.math.BigDecimal;

public record MenuRow(
        Long categoryId, // Идентификатор категории.
        String categoryName, // Название категории.
        Integer categorySortOrder, // Порядок категории в меню.

        Long itemId,
        String itemName,
        String itemDescription,
        BigDecimal itemPrice,
        Boolean itemAvailable,
        Integer itemSortOrder
) {
}
