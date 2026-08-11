package com.dima.fooddelivery.payment.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Payment(
        Long id,
        Long orderId,
        Long customerId,
        BigDecimal amount,
        PaymentStatus status,
        PaymentMethod method,
        String idempotencyKey,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
