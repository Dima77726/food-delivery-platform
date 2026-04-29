package com.dima.fooddelivery.order.persistence;

import java.math.BigDecimal;

public record OrderRow(
        Long orderId,
        Long cartId,
        Long customerId,
        Long restaurantId,
        String orderStatus,
        BigDecimal totalAmount,

        Long orderItemId,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal
) {
}
