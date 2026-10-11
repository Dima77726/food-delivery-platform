package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.domain.AuditRecord;
import com.dima.fooddelivery.contracts.audit.AuditEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Разбор записи сводного журнала — без Kafka и ClickHouse.
 *
 * <p>Тело собирают order-service и identity-service, сериализуя {@link AuditEvent} тем же
 * Jackson. Тест сериализует запись так же, как они, и проверяет, что разбор ничего не теряет:
 * ни отсутствующего автора, ни момента действия при смене часового пояса.
 */
class AuditEventsConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final AuditEventsConsumer consumer = new AuditEventsConsumer(null, objectMapper);

    @Test
    void shouldParseEventWrittenByProducer() {
        AuditEvent event = new AuditEvent(
                AuditEvent.eventIdOf("order-service", 42L),
                AuditEvent.CURRENT_SCHEMA_VERSION,
                "order-service",
                42L,
                null,
                null,
                "ORDER_CANCELED",
                "ORDER",
                "9",
                "SUCCESS",
                "Отменён по таймауту оплаты",
                "trace-1",
                OffsetDateTime.of(2026, 1, 15, 15, 0, 0, 0, ZoneOffset.ofHours(3))
        );

        AuditRecord record = consumer.parse(recordOf(objectMapper.writeValueAsString(event)));

        assertAll(
                () -> assertEquals(event.eventId(), record.eventId()),
                () -> assertEquals(42L, record.sourceId()),
                () -> assertNull(record.actorId(), "системное действие не должно получить автора"),
                () -> assertEquals("ORDER_CANCELED", record.action()),
                () -> assertEquals("9", record.resourceId()),
                () -> assertEquals("trace-1", record.correlationId()),
                // 15:00 по Москве — это 12:00 UTC: момент тот же, запись приведена к одному поясу.
                () -> assertEquals(Instant.parse("2026-01-15T12:00:00Z"), record.occurredAt())
        );
    }

    /** Битое сообщение пропускается, а не роняет пачку: иначе встал бы весь журнал. */
    @Test
    void shouldSkipUnparseableMessage() {
        assertNull(consumer.parse(recordOf("{не json")));
    }

    private static ConsumerRecord<String, String> recordOf(String value) {
        return new ConsumerRecord<>("food-delivery.audit-events", 0, 0L, "ORDER:9", value);
    }
}
