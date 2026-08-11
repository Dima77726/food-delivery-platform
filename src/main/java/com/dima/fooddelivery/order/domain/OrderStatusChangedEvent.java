package com.dima.fooddelivery.order.domain;

/**
 * Событие смены статуса заказа внутри приложения.
 *
 * <p>Через него модуль Order сообщает о произошедшем, не зная, кто слушает. Иначе
 * {@code OrderStatusService} пришлось бы дать ссылку на {@code NotificationService},
 * а потом на аудит, потом на аналитику — и модуль-владелец заказа собрал бы вокруг себя
 * зависимости на половину системы.
 *
 * <p>Это же место, куда позже встанет Kafka: публикация останется, поменяется транспорт.
 */
public record OrderStatusChangedEvent(
        Long orderId,
        Long customerId,
        Long restaurantId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        OrderEventType eventType
) {
}
