package com.dima.fooddelivery.contracts.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Исход платежа, публикуемый сервисом платежей.
 *
 * <p>Правила те же, что у {@code OrderIntegrationEvent}: поля только добавляются, типы —
 * строками, {@code eventId} — ключ идемпотентности для потребителя.
 *
 * @param paymentId     идентификатор платежа в сервисе платежей; для отказа без созданного
 *                      платежа может быть пустым
 * @param failureReason причина отказа — только для {@link #PAYMENT_FAILED}
 */
public record PaymentEvent(
        UUID eventId,
        int schemaVersion,
        String eventType,
        Long orderId,
        Long paymentId,
        Long customerId,
        BigDecimal amount,
        String method,
        String failureReason,
        OffsetDateTime occurredAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";

    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
}
