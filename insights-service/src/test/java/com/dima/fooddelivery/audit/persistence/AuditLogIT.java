package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.audit.domain.AuditFilter;
import com.dima.fooddelivery.audit.domain.AuditRecord;
import com.dima.fooddelivery.contracts.audit.AuditEvent;
import com.dima.fooddelivery.support.AbstractStoresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Сводный журнал аудита на настоящем ClickHouse.
 *
 * <p>Как и в {@code OrderAnalyticsIT}, главная проверка — дубликаты: повторная доставка
 * ложится в таблицу второй строкой, и ответ журнала обязан этого не заметить. Записи
 * вставляются репозиторием, минуя Kafka.
 *
 * <p>Хранилище между тестами не чистится, поэтому у каждого теста свой ресурс.
 */
class AuditLogIT extends AbstractStoresIntegrationTest {

    private static final AtomicLong SOURCE_IDS = new AtomicLong(System.nanoTime());

    private static final Instant NOON = Instant.parse("2026-01-15T12:00:00Z");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void shouldReturnEveryFieldAndKeepNullsNull() {
        String orderId = uniqueResource();
        AuditRecord written = record("order-service", null, "ORDER", orderId, NOON);

        auditLogRepository.insertBatch(List.of(written));

        AuditRecord read = single(new AuditFilter(null, "ORDER", orderId, null));

        assertAll(
                () -> assertEquals(written.eventId(), read.eventId()),
                () -> assertEquals("order-service", read.sourceService()),
                () -> assertEquals(written.sourceId(), read.sourceId()),
                () -> assertNull(read.actorId(), "Nullable не должен превращаться в ноль"),
                () -> assertNull(read.actorEmail()),
                () -> assertEquals("ORDER_CANCELED", read.action()),
                () -> assertEquals(orderId, read.resourceId()),
                () -> assertEquals("SUCCESS", read.outcome()),
                () -> assertNull(read.details()),
                () -> assertEquals("trace-" + written.sourceId(), read.correlationId()),
                () -> assertEquals(NOON, read.occurredAt())
        );
    }

    /** Та же запись, доставленная дважды, — одна строка в ответе. */
    @Test
    void shouldCollapseRedeliveredRecord() {
        String orderId = uniqueResource();
        AuditRecord record = record("order-service", 7L, "ORDER", orderId, NOON);

        auditLogRepository.insertBatch(List.of(record));
        auditLogRepository.insertBatch(List.of(record));

        assertEquals(1, auditLogRepository.find(new AuditFilter(null, "ORDER", orderId, null), 10).size());
    }

    /**
     * История ресурса — свежие сверху, и фильтры по сервису и автору не перепутаны местами:
     * все они строки или числа, и перестановка параметров компилируется молча.
     */
    @Test
    void shouldFilterAndOrderNewestFirst() {
        long actorId = SOURCE_IDS.incrementAndGet();
        String userId = uniqueResource();

        AuditRecord older = record("identity-service", actorId, "USER", userId, NOON);
        AuditRecord newer = record("identity-service", actorId, "USER", userId, NOON.plusSeconds(60));
        AuditRecord otherActor = record("order-service", actorId + 1, "USER", userId, NOON.plusSeconds(120));

        auditLogRepository.insertBatch(List.of(older, newer, otherActor));

        List<AuditRecord> history = auditLogRepository.find(new AuditFilter(null, "USER", userId, null), 10);
        List<AuditRecord> byActor = auditLogRepository.find(new AuditFilter(null, null, null, actorId), 10);
        List<AuditRecord> byService = auditLogRepository.find(
                new AuditFilter("order-service", "USER", userId, null), 10
        );

        assertAll(
                () -> assertEquals(
                        List.of(otherActor.eventId(), newer.eventId(), older.eventId()),
                        history.stream().map(AuditRecord::eventId).toList(),
                        "свежие записи сверху"
                ),
                () -> assertEquals(
                        List.of(newer.eventId(), older.eventId()),
                        byActor.stream().map(AuditRecord::eventId).toList()
                ),
                () -> assertEquals(
                        List.of(otherActor.eventId()),
                        byService.stream().map(AuditRecord::eventId).toList()
                ),
                () -> assertEquals(1, auditLogRepository.find(new AuditFilter(null, "USER", userId, null), 1).size(),
                        "лимит обязан соблюдаться")
        );
    }

    private AuditRecord single(AuditFilter filter) {
        List<AuditRecord> found = auditLogRepository.find(filter, 10);

        assertEquals(1, found.size(), "ожидалась ровно одна запись");

        return found.get(0);
    }

    private static String uniqueResource() {
        return String.valueOf(SOURCE_IDS.incrementAndGet());
    }

    private static AuditRecord record(
            String sourceService,
            Long actorId,
            String resourceType,
            String resourceId,
            Instant occurredAt
    ) {
        long sourceId = SOURCE_IDS.incrementAndGet();

        return new AuditRecord(
                AuditEvent.eventIdOf(sourceService, sourceId),
                sourceService,
                sourceId,
                actorId,
                actorId == null ? null : "actor-" + actorId + "@example.test",
                "ORDER_CANCELED",
                resourceType,
                resourceId,
                "SUCCESS",
                null,
                "trace-" + sourceId,
                occurredAt
        );
    }
}
