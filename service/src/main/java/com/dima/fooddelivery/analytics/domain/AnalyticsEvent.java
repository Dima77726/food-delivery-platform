package com.dima.fooddelivery.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Строка витрины аналитики: событие заказа в том виде, в каком оно ложится в ClickHouse.
 *
 * <p>Это копия события из Kafka, а не самостоятельные данные. Витрина производная: её можно
 * удалить целиком и наполнить заново, перечитав топик с начала. Отсюда и свобода в схеме —
 * денормализация, отсутствие внешних ключей и хранение статуса строкой.
 *
 * <p>{@code eventId} переезжает сюда не для красоты: он единственное, что позволяет отличить
 * повторную доставку того же события от двух разных. Гарантия Kafka - "хотя бы один раз",
 * и дубликаты здесь неизбежны.
 */
public record AnalyticsEvent(
        UUID eventId,
        String eventType,
        Long orderId,
        Long customerId,
        Long restaurantId,
        String previousStatus,
        String newStatus,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}
