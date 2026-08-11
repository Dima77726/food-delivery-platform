package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.service.DeliveryService;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.payment.api.PayOrderRequest;
import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.service.PaymentService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Полный путь заказа через публичные методы сервисов — от корзины до доставки.
 *
 * <p>Этот тест намеренно проходит через все модули сразу: он проверяет не отдельный метод,
 * а то, что модули стыкуются между собой. Если кто-то сломает контракт между Order и Delivery,
 * упадёт именно он, а не десять узких тестов по одному.
 */
class OrderLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private NotificationService notificationService;

    @Test
    void shouldCarryOrderFromCartToDelivered() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 2));

        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(OrderStatus.CREATED.getDbValue(), created.status()),
                () -> assertEquals(new BigDecimal("900.00"), created.totalAmount()),
                () -> assertEquals(1, created.items().size()),
                () -> assertEquals(2, created.items().get(0).quantity())
        );

        Long orderId = created.id();

        paymentService.payForOrder(customerId, orderId, cardPayment());
        assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(orderId));

        orderService.acceptOrder(restaurantId, orderId);
        orderService.startCookingOrder(restaurantId, orderId);
        orderService.markOrderReadyForDelivery(restaurantId, orderId);
        assertEquals(OrderStatus.READY_FOR_DELIVERY.getDbValue(), testData.currentOrderStatus(orderId));

        DeliveryResponse delivery = deliveryService.createDeliveryForOrder(orderId);
        deliveryService.assignCourierToDelivery(courierId, delivery.id());
        deliveryService.pickUpDelivery(courierId, delivery.id());
        assertEquals(OrderStatus.IN_DELIVERY.getDbValue(), testData.currentOrderStatus(orderId));

        deliveryService.deliverDelivery(courierId, delivery.id());

        assertAll(
                () -> assertEquals(OrderStatus.DELIVERED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(
                        7,
                        orderService.getOrderEventsForCustomer(customerId, orderId).size(),
                        "в истории должны быть создание, оплата и пять переходов"
                ),
                () -> assertFalse(
                        notificationService.getNotificationsForUser(customerId).isEmpty(),
                        "клиент должен получить уведомления о ходе заказа"
                )
        );
    }

    @Test
    void shouldNotAllowRestaurantToAcceptUnpaidOrder() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("300.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        // Появление модуля Payment сделало PAID обязательным шагом: до этого ресторан
        // принимал заказ сразу из CREATED и начинал готовить за свой счёт.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> orderService.acceptOrder(restaurantId, created.id())
        );
    }

    @Test
    void shouldRefundMoneyWhenPaidOrderIsCanceled() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("500.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        paymentService.payForOrder(customerId, created.id(), cardPayment());
        orderService.cancelOrder(customerId, created.id());

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(created.id())),
                () -> assertEquals(
                        1,
                        testData.countOrderEvents(created.id(), "ORDER_REFUNDED"),
                        "за отменённый оплаченный заказ обязан появиться возврат"
                )
        );
    }

    @Test
    void shouldNotCreateSecondOrderFromSameCart() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("200.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        orderService.createOrderFromActiveCart(customerId, restaurantId);

        // Корзина ушла в CHECKED_OUT, активной больше нет — второй заказ создать не из чего.
        assertThrows(
                RuntimeException.class,
                () -> orderService.createOrderFromActiveCart(customerId, restaurantId)
        );
    }

    private PayOrderRequest cardPayment() {
        return new PayOrderRequest(PaymentMethod.CARD, UUID.randomUUID().toString());
    }
}
