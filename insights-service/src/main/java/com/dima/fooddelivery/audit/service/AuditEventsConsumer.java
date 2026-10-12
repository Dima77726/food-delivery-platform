package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.domain.AuditRecord;
import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.contracts.audit.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Собирает сводный журнал аудита из топика, куда пишут order-service и identity-service.
 *
 * <p>Своя группа потребителей — не та, что у аналитики: журнал можно перечитать с начала,
 * не трогая витрину заказов, и наоборот.
 *
 * <p>Повторная доставка безвредна: копия записи схлопнется в ReplacingMergeTree, а до того
 * её отсечёт {@code FINAL} при чтении (см. {@code AuditLogSchema}).
 *
 * <p><b>Неразбираемое сообщение пропускается</b> с ошибкой в логе. Ждать на нём бессмысленно —
 * оно не станет разбираемым от повтора, — а встал бы весь журнал. Сама запись при этом
 * не потеряна: оригинал лежит в журнале сервиса-источника, и по координатам из лога его
 * можно найти и переотправить, сбросив отметку {@code published_at}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AuditEventsConsumer {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.AUDIT_EVENTS,
            groupId = "${app.stores.clickhouse.audit-consumer-group:food-delivery-audit}",
            containerFactory = AuditKafkaConfig.LISTENER_FACTORY
    )
    public void onAuditEvents(List<ConsumerRecord<String, String>> records) {
        List<AuditRecord> parsed = new ArrayList<>(records.size());

        for (ConsumerRecord<String, String> record : records) {
            AuditRecord auditRecord = parse(record);

            if (auditRecord != null) {
                parsed.add(auditRecord);
            }
        }

        int written = auditLogRepository.insertBatch(parsed);

        log.debug("В сводный журнал аудита записано {} из пачки в {}", written, records.size());
    }

    /** @return строка журнала или {@code null}, если сообщение не разобрано */
    AuditRecord parse(ConsumerRecord<String, String> record) {
        try {
            AuditEvent event = objectMapper.readValue(record.value(), AuditEvent.class);

            return new AuditRecord(
                    event.eventId(),
                    event.sourceService(),
                    event.sourceId(),
                    event.actorId(),
                    event.actorEmail(),
                    event.action(),
                    event.resourceType(),
                    event.resourceId(),
                    event.outcome(),
                    event.details(),
                    event.correlationId(),
                    event.occurredAt().toInstant()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Запись аудита не разобрана и пропущена: topic={}, partition={}, offset={}, key={}, reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    exception.getMessage()
            );

            return null;
        }
    }
}
