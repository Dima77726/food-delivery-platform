package com.dima.fooddelivery.audit.api;

import com.dima.fooddelivery.audit.domain.AuditOutcome;

import java.time.OffsetDateTime;

public record AuditEntryResponse(
        Long id,
        Long actorId,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String details,
        OffsetDateTime createdAt
) {
}
