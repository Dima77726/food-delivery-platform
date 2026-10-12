package com.dima.fooddelivery.audit.domain;

/**
 * Фильтр по сводному журналу. Любое поле может быть {@code null} — тогда по нему
 * не фильтруется; все {@code null} — последние записи всех сервисов.
 *
 * @param sourceService {@code order-service}, {@code identity-service}
 * @param resourceType  {@code ORDER}, {@code USER}
 */
public record AuditFilter(
        String sourceService,
        String resourceType,
        String resourceId,
        Long actorId
) {
}
