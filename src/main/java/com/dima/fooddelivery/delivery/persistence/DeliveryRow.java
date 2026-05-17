package com.dima.fooddelivery.delivery.persistence;

import java.time.OffsetDateTime;

public record DeliveryRow(
        Long id,
        Long orderId,
        Long courierId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime pickedUpAt,
        OffsetDateTime deliveredAt
) {
}
