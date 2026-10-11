package com.dima.fooddelivery.audit.api;

import com.dima.fooddelivery.audit.domain.AuditFilter;
import com.dima.fooddelivery.audit.domain.AuditRecord;
import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import com.dima.fooddelivery.common.stores.StoreToggles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Сводный журнал аудита: действия со всех сервисов в одном месте.
 *
 * <p>Раньше журнал заказов отдавал order-service, а журнал пользователей не отдавал никто —
 * его читали SQL-запросом к базе identity-service. Теперь оба уезжают в Kafka и собираются
 * здесь, и шлюз ведёт сюда весь {@code /api/v1/admin/audit/**}.
 *
 * <p>Контроллер обращается к репозиторию напрямую: сервисного слоя здесь нечему наполнять —
 * ни правил, ни проверок, только чтение по фильтру.
 *
 * <p>Журнал живёт в ClickHouse и включается вместе с ним ({@code STORE_CLICKHOUSE_ENABLED}).
 * Пока хранилище выключено, маршрута нет, а записи копятся в топике и в журналах сервисов
 * и приедут, когда его включат.
 *
 * <p>{@code /api/v1/admin/**} закрыт ролью ADMIN в цепочке фильтров; {@code @PreAuthorize}
 * дублирует проверку на случай, если цепочку однажды перепишут.
 */
@Validated
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
@Tag(name = "Audit", description = "Сводный журнал аудита всех сервисов (ClickHouse)")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 500;

    private final AuditLogRepository auditLogRepository;

    @GetMapping("/api/v1/admin/audit")
    @Operation(summary = "Последние записи сводного журнала аудита, с фильтрами")
    public List<AuditEntryResponse> getAuditLog(
            @Parameter(description = "Сервис-источник: order-service, identity-service")
            @RequestParam(required = false) String service,

            @Parameter(description = "Тип ресурса: ORDER, USER")
            @RequestParam(required = false) String resourceType,

            @Parameter(description = "Идентификатор ресурса; осмыслен вместе с resourceType")
            @RequestParam(required = false) String resourceId,

            @Parameter(description = "Кто выполнил действие")
            @RequestParam(required = false) @Positive Long actorId,

            @RequestParam(defaultValue = "100")
            @Positive
            @Max(value = MAX_PAGE_SIZE, message = "limit не больше " + MAX_PAGE_SIZE)
            int limit
    ) {
        AuditFilter filter = new AuditFilter(
                blankToNull(service),
                blankToNull(resourceType),
                blankToNull(resourceId),
                actorId
        );

        return auditLogRepository.find(filter, limit).stream()
                .map(AuditController::toResponse)
                .toList();
    }

    /** {@code ?service=} без значения — то же, что фильтра нет, а не «сервис с пустым именем». */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static AuditEntryResponse toResponse(AuditRecord record) {
        return new AuditEntryResponse(
                record.eventId(),
                record.sourceService(),
                record.sourceId(),
                record.actorId(),
                record.actorEmail(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.outcome(),
                record.details(),
                record.correlationId(),
                record.occurredAt()
        );
    }
}
