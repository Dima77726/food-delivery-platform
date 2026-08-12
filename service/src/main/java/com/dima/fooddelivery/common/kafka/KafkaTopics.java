package com.dima.fooddelivery.common.kafka;

/**
 * Имена топиков.
 *
 * <p>Точка в имени — общепринятый разделитель пространств имён: по префиксу
 * {@code food-delivery.} видно, чьи это топики, когда кластер общий на несколько систем.
 *
 * <p>Топик разбора ошибок называется тем же именем с суффиксом {@code .DLT} — это
 * соглашение Spring Kafka по умолчанию, и ломать его без причины не стоит.
 */
public final class KafkaTopics {

    /** События жизненного цикла заказа. Ключ сообщения — идентификатор заказа. */
    public static final String ORDER_EVENTS = "food-delivery.order-events";

    /** Сообщения, которые не удалось обработать. */
    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS + ".DLT";

    private KafkaTopics() {
    }
}
