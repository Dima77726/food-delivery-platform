package com.dima.fooddelivery.audit.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись сводного журнала аудита.
 *
 * @param sourceService сервис, где произошло действие
 * @param sourceId      номер записи в журнале этого сервиса — по нему запись находится в источнике
 * @param actorId       {@code null} — действие системы (например, отмена заказа по таймауту)
 * @param correlationId метка запроса: по ней запись сшивается с логами всех сервисов,
 *                      через которые прошёл запрос
 */
public record AuditEntryResponse(
        UUID eventId,
        String sourceService,
        long sourceId,
        Long actorId,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String details,
        String correlationId,
        Instant occurredAt
) {
}
