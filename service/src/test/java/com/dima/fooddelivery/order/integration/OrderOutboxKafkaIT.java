package com.dima.fooddelivery.order.integration;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.OutboxRepository;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.order.service.OutboxPublisher;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сквозной путь события: смена статуса заказа → outbox → Kafka → уведомление.
 *
 * <p><b>Почему без {@code @Transactional}.</b> Все остальные интеграционные тесты откатывают
 * транзакцию после себя, и это удобно. Здесь так нельзя: смысл outbox в том, что событие
 * становится видимым публикатору только после коммита. В откатываемой транзакции запись
 * никогда не была бы зафиксирована, публикатор её не увидел бы, и тест проверял бы пустоту.
 *
 * <p>Плата — данные остаются в базе после теста. Для проверки, которая доказывает
 * согласованность записи и публикации, это честная цена.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderOutboxKafkaIT extends AbstractIntegrationTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Адрес брокера берётся у контейнера, а не из {@code spring.kafka.bootstrap-servers}.
     *
     * <p>{@code @ServiceConnection} не переписывает свойства окружения: он отдаёт
     * автоконфигурации бин {@code KafkaConnectionDetails}, а в {@code application.yml}
     * остаётся значение по умолчанию — {@code localhost:9094}. Бины Spring подключаются
     * куда надо, а вот собранный вручную потребитель по этому свойству ушёл бы в пустоту
     * и молча ничего не дождался.
     */
    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    private void awaitTrue(BooleanSupplier condition, String description) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание прервано", interrupted);
            }
        }

        throw new AssertionError("Не дождались за " + DELIVERY_TIMEOUT + ": " + description);
    }

    @Test
    void shouldWriteEventToOutboxInSameTransactionAsStatusChange() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        orderStatusService.markPaid(order.orderId());

        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_event_outbox WHERE aggregate_id = ? AND status = 'PENDING'",
                Integer.class,
                order.orderId()
        );

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                order.orderId()
        );

        assertAll(
                () -> assertEquals(1, outboxRows, "смена статуса обязана оставить запись в outbox"),
                () -> assertTrue(payload.contains("\"newStatus\":\"PAID\"")),
                () -> assertTrue(payload.contains("\"schemaVersion\":1"), "версия схемы должна попадать в контракт")
        );
    }

    /**
     * Полный путь. Публикация вызывается вручную, доставка ожидается — между отправкой
     * в Kafka и обработкой у потребителя проходит время, и притворяться, что оно нулевое,
     * значит писать флейкающий тест.
     */
    @Test
    void shouldDeliverEventToConsumerAndCreateNotification() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        orderStatusService.markPaid(order.orderId());

        int published = outboxPublisher.publishBatch();
        assertTrue(published > 0, "публикатор должен был отправить событие");

        awaitTrue(
                () -> !notificationService.getNotificationsForUser(customerId).isEmpty(),
                "уведомление по событию из Kafka так и не появилось"
        );

        List<Notification> notifications = notificationService.getNotificationsForUser(customerId);

        assertAll(
                () -> assertEquals(1, notifications.size()),
                () -> assertEquals("ORDER_PAID", notifications.get(0).type()),
                () -> assertTrue(notifications.get(0).body().contains("Оплата прошла")),
                () -> assertEquals(order.orderId(), notifications.get(0).orderId())
        );
    }

    @Test
    void shouldMarkOutboxRecordPublishedAfterSuccessfulSend() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        orderStatusService.markPaid(order.orderId());
        outboxPublisher.publishBatch();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                order.orderId()
        );

        assertAll(
                () -> assertEquals("PUBLISHED", status),
                () -> assertFalse(
                        outboxRepository.lockPending(10).stream()
                                .anyMatch(record -> record.aggregateId().equals(order.orderId())),
                        "опубликованное событие не должно попадать в следующую выборку"
                )
        );
    }

    /**
     * Повторная доставка неизбежна при гарантии «хотя бы один раз»: между отправкой и
     * отметкой PUBLISHED процесс может умереть. Проверяем, что второй проход по тому же
     * событию не порождает второе уведомление.
     */
    @Test
    void shouldNotCreateDuplicateNotificationOnRedelivery() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        orderStatusService.markPaid(order.orderId());
        outboxPublisher.publishBatch();

        awaitTrue(
                () -> !notificationService.getNotificationsForUser(customerId).isEmpty(),
                "первое уведомление не появилось"
        );

        // Возвращаем запись в PENDING и публикуем снова — ровно то, что происходит
        // при падении процесса между отправкой и отметкой.
        jdbcTemplate.update(
                "UPDATE order_event_outbox SET status = 'PENDING', published_at = NULL WHERE aggregate_id = ?",
                order.orderId()
        );

        outboxPublisher.publishBatch();

        // Ждём заведомо дольше, чем идёт доставка: если дубликат появится, он появится здесь.
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        assertEquals(
                1,
                notificationService.getNotificationsForUser(customerId).size(),
                "повторная доставка события не должна порождать второе уведомление"
        );
    }

    /**
     * Метка запроса обязана пережить асинхронную границу.
     *
     * <p>Это и есть смысл всей затеи: уведомление создаётся в другом потоке и через
     * несколько секунд после запроса, а связать его с этим запросом всё равно возможно.
     * Проверяется вся цепочка переноса — MDC потока запроса, колонка в outbox, заголовок
     * сообщения Kafka и, наконец, колонка в уведомлении.
     */
    @Test
    void shouldCarryCorrelationIdFromRequestThreadToNotification() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        String correlationId = "trace-" + UUID.randomUUID();

        // Смена статуса выполняется так, будто её вызвал HTTP-запрос: именно из MDC
        // слушатель outbox и берёт метку.
        MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        try {
            orderStatusService.markPaid(order.orderId());
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }

        String inOutbox = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                order.orderId()
        );

        outboxPublisher.publishBatch();

        awaitTrue(
                () -> !notificationService.getNotificationsForUser(customerId).isEmpty(),
                "уведомление так и не появилось"
        );

        String inNotification = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM notification WHERE recipient_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                customerId
        );

        assertAll(
                () -> assertEquals(correlationId, inOutbox, "метка обязана лечь в outbox вместе с событием"),
                () -> assertEquals(
                        correlationId,
                        inNotification,
                        "метка обязана доехать до уведомления через заголовок сообщения Kafka"
                )
        );
    }

    /**
     * Неразбираемое сообщение обязано уехать в топик разбора.
     *
     * <p>Это самая важная проверка из всех здесь: без работающего обработчика ошибок
     * одно битое сообщение останавливает потребителя навсегда. Kafka не двигает офсет,
     * пока обработка не завершилась успехом, поэтому потребитель будет читать его снова
     * и снова, а весь топик встанет за ним — в отличие от очереди, где сообщение можно
     * просто отбросить. Проверяется, что предел попыток есть и что за ним лежит DLT,
     * а не бесконечный цикл.
     *
     * <p>DLT читается отдельным потребителем, собранным вручную. Слушатель приложения
     * пишет о таких сообщениях в лог, а утверждать по логам — плохая опора для теста.
     */
    @Test
    void shouldRouteUnparsableMessageToDeadLetterTopic() {
        String key = "broken-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> dltConsumer = createDltConsumer()) {
            dltConsumer.subscribe(List.of(KafkaTopics.ORDER_EVENTS_DLT));
            // Первый poll заодно назначает партиции: до него подписка ещё не вступила в силу.
            dltConsumer.poll(Duration.ofSeconds(5));

            kafkaTemplate.send(KafkaTopics.ORDER_EVENTS, key, "не json вовсе");

            ConsumerRecord<String, String> failed = awaitDltRecord(dltConsumer, key);

            assertAll(
                    () -> assertNotNull(failed, "битое сообщение так и не доехало до DLT"),
                    () -> assertEquals("не json вовсе", failed.value(), "тело должно уехать без изменений"),
                    () -> assertNotNull(
                            failed.headers().lastHeader("kafka_dlt-exception-message"),
                            "в DLT должна попадать причина отказа"
                    )
            );
        }
    }

    /**
     * Своя группа, чтобы чтение теста не сдвигало офсеты слушателя приложения,
     * и {@code earliest} — сообщение может оказаться в топике раньше, чем потребитель
     * получит партиции.
     */
    private KafkaConsumer<String, String> createDltConsumer() {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Ожидание длиннее обычного намеренно: прежде чем сдаться, обработчик делает три
     * попытки с паузой в секунду, и только потом публикует сообщение в DLT.
     */
    private ConsumerRecord<String, String> awaitDltRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались сообщения в DLT за " + DELIVERY_TIMEOUT);
    }
}
