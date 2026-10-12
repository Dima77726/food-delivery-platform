package com.dima.fooddelivery.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Строка сводного журнала аудита.
 *
 * <p>Копия записи из журнала сервиса-источника, а не самостоятельные данные: источник истины —
 * таблица {@code audit_log} в базе order-service или identity-service, где запись легла в одной
 * транзакции с действием. Сводный журнал можно удалить и собрать заново, перечитав топик.
 *
 * <p>Пара {@code sourceService} + {@code sourceId} указывает на исходную запись, а
 * {@code eventId} выведен из неё и отличает повторную доставку от новой записи.
 *
 * @param actorId {@code null} — действие системы, а не человека
 */
public record AuditRecord(
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
