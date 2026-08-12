package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.order.persistence.OutboxRecord;
import com.dima.fooddelivery.order.persistence.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет накопившиеся события из outbox в Kafka.
 *
 * <p>Вторая половина паттерна: первая записала событие в базу вместе с заказом, эта —
 * доставляет его наружу.
 *
 * <p><b>Доставка «хотя бы один раз».</b> Между успешной отправкой в Kafka и отметкой
 * PUBLISHED процесс может умереть — тогда после перезапуска событие уйдёт повторно.
 * Сделать «ровно один раз» без распределённой транзакции нельзя, поэтому выбран честный
 * компромисс: лучше дубль, который отсеет потребитель по {@code eventId}, чем потерянное
 * событие, которое не восстановит никто.
 *
 * <p>Расписанием занимается {@link OutboxPublishJob} — здесь только сама работа. Тесты
 * пользуются этим и вызывают {@link #publishBatch()} напрямую, когда им нужно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    /**
     * Пачка небольшая: пока она обрабатывается, строки заблокированы, а внутри цикла
     * идёт сетевой вызов.
     */
    private static final int BATCH_SIZE = 50;

    /** Сколько раз пытаться, прежде чем признать событие неотправляемым. */
    private static final int MAX_ATTEMPTS = 10;

    /** Ждать подтверждения дольше нет смысла: следующий запуск планировщика попробует снова. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Вся пачка в одной транзакции: блокировка из {@code SKIP LOCKED} живёт ровно до её конца.
     *
     * <p>Отправка идёт синхронно, с ожиданием подтверждения от брокера. Асинхронная отправка
     * без ожидания была бы быстрее, но тогда отметка PUBLISHED ставилась бы до того, как
     * Kafka подтвердила запись, — и при падении брокера событие считалось бы доставленным,
     * не будучи таковым.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRecord> pending = outboxRepository.lockPending(BATCH_SIZE);

        int published = 0;
        for (OutboxRecord record : pending) {
            if (publishOne(record)) {
                published++;
            }
        }

        return published;
    }

    private boolean publishOne(OutboxRecord record) {
        try {
            // Ключ — идентификатор заказа. Только он гарантирует, что события одного заказа
            // попадут в одну партицию и придут потребителю по порядку. Без ключа Kafka
            // раскидает их по партициям, и «доставлен» может обогнать «оплачен».
            kafkaTemplate
                    .send(KafkaTopics.ORDER_EVENTS, String.valueOf(record.aggregateId()), record.payload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            outboxRepository.markPublished(record.id());

            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outboxRepository.recordFailure(record.id(), "Отправка прервана", MAX_ATTEMPTS);

            return false;
        } catch (Exception exception) {
            log.warn(
                    "Не удалось опубликовать событие: outboxId={}, eventId={}, attempts={}, reason={}",
                    record.id(),
                    record.eventId(),
                    record.attempts(),
                    exception.getMessage()
            );

            outboxRepository.recordFailure(record.id(), exception.getMessage(), MAX_ATTEMPTS);

            return false;
        }
    }
}
