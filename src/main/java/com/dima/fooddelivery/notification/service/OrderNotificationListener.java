package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Превращает смену статуса заказа в уведомление клиенту.
 *
 * <p>{@code @EventListener}, а не {@code @TransactionalEventListener(AFTER_COMMIT)}: слушатель
 * только кладёт строку в таблицу очереди, и она обязана откатиться вместе с заказом, если
 * транзакция не завершится. Отправка наружу происходит позже и вне транзакции —
 * см. {@link NotificationService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationListener {

    /**
     * Не на каждый переход нужно уведомление: клиенту важны оплата, принятие, выезд курьера
     * и доставка. Промежуточная «готовка началась» его только раздражает.
     */
    private static final Map<OrderStatus, String> CUSTOMER_MESSAGES = Map.of(
            OrderStatus.PAID, "Оплата прошла, заказ передан в ресторан",
            OrderStatus.ACCEPTED, "Ресторан принял ваш заказ",
            OrderStatus.READY_FOR_DELIVERY, "Заказ готов и ждёт курьера",
            OrderStatus.IN_DELIVERY, "Курьер забрал заказ и везёт его вам",
            OrderStatus.DELIVERED, "Заказ доставлен. Приятного аппетита",
            OrderStatus.CANCELED, "Заказ отменён"
    );

    private final NotificationService notificationService;

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        String message = CUSTOMER_MESSAGES.get(event.newStatus());

        if (message == null) {
            return;
        }

        notificationService.enqueue(
                event.customerId(),
                NotificationChannel.EMAIL,
                event.eventType().getDbValue(),
                "Заказ №" + event.orderId(),
                message,
                event.orderId()
        );
    }
}
