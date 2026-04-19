package com.dima.fooddelivery.cart.persistence;

import java.math.BigDecimal;

public record CartRow(
        Long cartId,
        Long customerId,
        Long restaurantId,
        String cartStatus,

        Long cartItemId,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price
) {
}
