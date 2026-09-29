package com.dima.fooddelivery.analytics.service;

import com.dima.fooddelivery.analytics.domain.AnalyticsEvent;
import com.dima.fooddelivery.analytics.persistence.OrderAnalyticsRepository;
import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.integration.OrderIntegrationEvent;
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
 * Наполнение витрины аналитики из топика заказов.
 *
 * <p><b>Своя группа потребителей.</b> Тот же топик уже читает слушатель уведомлений, и это
 * ровно тот случай, ради которого события вынесены в Kafka: два независимых потребителя
 * читают одну ленту, каждый со своим офсетом, и ни один не мешает другому. Уведомления
 * можно выключить, аналитику остановить и перечитать топик с начала — модуль Order
 * ни о том, ни о другом не знает.
 *
 * <p><b>Перечитывание с начала работает.</b> Витрина производная: удалить таблицу, сбросить
 * офсет группы и дать потребителю прочитать топик заново — законная операция восстановления,
 * а не аварийный трюк. Дубликаты, которые при этом появятся, витрина переживает по построению
 * (см. {@code OrderAnalyticsSchema}).
 *
 * <p><b>Неразбираемое сообщение пропускается, а не отправляется в DLT.</b> Это отличие
 * от слушателя уведомлений, и оно осознанное. Там пропуск означает не отправленное клиенту
 * сообщение, здесь — одну строку, которой не хватит в отчёте. Останавливать наполнение
 * витрины из-за одного битого события хуже: следом встанет весь отчёт.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class OrderEventsAnalyticsConsumer {

    private final OrderAnalyticsRepository orderAnalyticsRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENTS,
            groupId = "${app.stores.clickhouse.consumer-group:food-delivery-analytics}",
            containerFactory = AnalyticsKafkaConfig.LISTENER_FACTORY
    )
    public void onOrderEvents(List<ConsumerRecord<String, String>> records) {
        List<AnalyticsEvent> events = new ArrayList<>(records.size());

        for (ConsumerRecord<String, String> record : records) {
            AnalyticsEvent event = parse(record);

            if (event != null) {
                events.add(event);
            }
        }

        int written = orderAnalyticsRepository.insertBatch(events);

        log.debug("В витрину аналитики записано {} событий из пачки в {}", written, records.size());
    }

    /**
     * @return строка витрины или {@code null}, если сообщение разобрать не удалось
     */
    private AnalyticsEvent parse(ConsumerRecord<String, String> record) {
        try {
            OrderIntegrationEvent event = objectMapper.readValue(record.value(), OrderIntegrationEvent.class);

            return new AnalyticsEvent(
                    event.eventId(),
                    event.eventType(),
                    event.orderId(),
                    event.customerId(),
                    event.restaurantId(),
                    event.previousStatus(),
                    event.newStatus(),
                    event.totalAmount(),
                    // occurredAt приходит со смещением, а в витрине время хранится в UTC.
                    // Момент от этого не меняется, меняется только запись — и хорошо:
                    // сравнивать даты в отчётах можно только приведёнными к одному поясу.
                    event.occurredAt().toInstant()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Событие не разобрано и пропущено: topic={}, partition={}, offset={}, reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception.getMessage()
            );

            return null;
        }
    }
}
