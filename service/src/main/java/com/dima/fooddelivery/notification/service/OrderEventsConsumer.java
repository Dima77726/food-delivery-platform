package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.order.integration.OrderIntegrationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Уведомления клиенту, порождённые событиями из Kafka.
 *
 * <p>Раньше это делал внутрипроцессный слушатель. Переезд на топик — не украшение:
 * теперь отдельный сервис уведомлений может подписаться на тот же топик и забрать эту
 * работу себе, а модуль Order об этом даже не узнает. Внутреннее событие такой возможности
 * не давало — оно живёт в памяти одного процесса.
 *
 * <p>Аудит и метрики намеренно остались на внутреннем событии. Журнал аудита — собственная
 * запись приложения о том, что оно сделало, и она обязана фиксироваться в одной транзакции
 * с изменением. Уведомление — сообщение наружу, и его задержка на секунду никого не смущает.
 *
 * <p><b>Идемпотентность.</b> Outbox доставляет «хотя бы один раз», значит дубли будут.
 * Защита — уникальный индекс по {@code event_id} в таблице уведомлений: повторная обработка
 * упирается в констрейнт, и это не ошибка, а сигнал «уже сделано».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsConsumer {

    /**
     * Не на каждый переход нужно уведомление: клиенту важны оплата, принятие, выезд курьера
     * и доставка. Промежуточная «готовка началась» его только раздражает.
     */
    private static final Map<String, String> CUSTOMER_MESSAGES = Map.of(
            "PAID", "Оплата прошла, заказ передан в ресторан",
            "ACCEPTED", "Ресторан принял ваш заказ",
            "READY_FOR_DELIVERY", "Заказ готов и ждёт курьера",
            "IN_DELIVERY", "Курьер забрал заказ и везёт его вам",
            "DELIVERED", "Заказ доставлен. Приятного аппетита",
            "CANCELED", "Заказ отменён"
    );

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENTS,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onOrderEvent(String payload) {
        OrderIntegrationEvent event = parse(payload);

        String message = CUSTOMER_MESSAGES.get(event.newStatus());

        if (message == null) {
            return;
        }

        try {
            notificationService.enqueueFromEvent(
                    event.eventId(),
                    event.customerId(),
                    NotificationChannel.EMAIL,
                    event.eventType(),
                    "Заказ №" + event.orderId(),
                    message,
                    event.orderId()
            );
        } catch (DuplicateKeyException duplicate) {
            // Повторная доставка того же события. Штатный исход при гарантии «хотя бы один
            // раз», а не ошибка: бросить исключение здесь означало бы отправить сообщение
            // в DLT и заставить человека разбираться с нормальным поведением системы.
            log.debug("Событие уже обработано, пропускаем: eventId={}", event.eventId());
        }
    }

    /**
     * Неразбираемое сообщение — повод для DLT, а не для бесконечных повторов: сколько
     * ни пытайся, JSON от этого не починится. Исключение выбрасывается наружу, где его
     * ловит настроенный обработчик ошибок.
     */
    private OrderIntegrationEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, OrderIntegrationEvent.class);
        } catch (RuntimeException exception) {
            log.error("Не удалось разобрать событие заказа: {}", exception.getMessage());

            throw exception;
        }
    }
}
