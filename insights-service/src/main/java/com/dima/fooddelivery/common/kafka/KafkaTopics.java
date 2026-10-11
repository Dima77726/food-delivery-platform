package com.dima.fooddelivery.common.kafka;

import com.dima.fooddelivery.contracts.audit.AuditTopics;
import com.dima.fooddelivery.contracts.order.OrderEventTopics;

/**
 * Имена топиков.
 *
 * <p>Точка в имени — общепринятый разделитель пространств имён: по префиксу
 * {@code food-delivery.} видно, чьи это топики, когда кластер общий на несколько систем.
 *
 * <p>Топик разбора ошибок называется тем же именем с суффиксом {@code .DLT} — соглашение
 * {@code @RetryableTopic}. Учтите: {@code DeadLetterPublishingRecoverer} сам по себе
 * приписывает другой суффикс, {@code -dlt}, поэтому в {@link KafkaConfig} назначение
 * задано явно. Менять имя здесь, не поправив там, — значит тихо потерять все отказы.
 */
public final class KafkaTopics {

    /**
     * События жизненного цикла заказа. Имя берётся из библиотеки контрактов: его читают
     * другие сервисы, и держать копию строки здесь значило бы разойтись с ними при первом
     * переименовании.
     */
    public static final String ORDER_EVENTS = OrderEventTopics.ORDER_EVENTS;

    /**
     * Сообщения, которые аналитика не смогла обработать.
     *
     * <p>Топик свой у каждого потребляющего сервиса: notification-service отправляет отказы
     * в собственный DLT и разбирает их сам.
     */
    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS + ".DLT";

    /**
     * Журнал аудита order-service и identity-service — сырьё сводного журнала. Топика разбора
     * у него нет: почему — в {@code AuditKafkaConfig}.
     */
    public static final String AUDIT_EVENTS = AuditTopics.AUDIT_EVENTS;

    private KafkaTopics() {
    }
}
