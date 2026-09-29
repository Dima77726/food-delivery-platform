package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.payment.api.PayOrderRequest;
import com.dima.fooddelivery.payment.api.PaymentResponse;
import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentServiceIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    void shouldPayForOrderAndMoveItToPaid() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        PaymentResponse payment = paymentService.payForOrder(customerId, order.orderId(), cardPayment());

        assertAll(
                () -> assertEquals(PaymentStatus.SUCCEEDED, payment.status()),
                () -> assertEquals(order.price(), payment.amount()),
                () -> assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(order.orderId()))
        );
    }

    /**
     * Ключевое свойство модуля: повтор запроса не списывает деньги дважды.
     * Так выглядит двойной клик по кнопке оплаты или ретрай после таймаута сети.
     */
    @Test
    void shouldReturnSamePaymentOnRepeatedRequestWithSameIdempotencyKey() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        PayOrderRequest request = cardPayment();

        PaymentResponse first = paymentService.payForOrder(customerId, order.orderId(), request);
        PaymentResponse second = paymentService.payForOrder(customerId, order.orderId(), request);

        List<PaymentResponse> payments = paymentService.getPaymentsForOrder(customerId, order.orderId());

        assertAll(
                () -> assertEquals(first.id(), second.id(), "повтор обязан вернуть тот же платёж"),
                () -> assertEquals(1, payments.size(), "второй строки в payment появиться не должно")
        );
    }

    @Test
    void shouldRecordFailedAttemptAndKeepOrderUnpaid() {
        Long customerId = testData.insertCustomer();

        // Сумма, оканчивающаяся на .13, отклоняется заглушкой шлюза — детерминированно,
        // без случайности, иначе тест был бы «мигающим».
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("100.13"));
        Long cartId = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
        Long orderId = testData.insertOrder(
                cartId, customerId, restaurantId, OrderStatus.CREATED, new BigDecimal("100.13")
        );
        testData.insertOrderItem(orderId, menuItemId, 1, new BigDecimal("100.13"));

        assertThrows(
                BusinessRuleViolationException.class,
                () -> paymentService.payForOrder(customerId, orderId, cardPayment())
        );

        assertAll(
                () -> assertEquals(
                        OrderStatus.CREATED.getDbValue(),
                        testData.currentOrderStatus(orderId),
                        "неудачная оплата не должна двигать заказ"
                ),
                () -> assertEquals(
                        1,
                        testData.countOrderEvents(orderId, "ORDER_PAYMENT_FAILED"),
                        "неудачная попытка обязана остаться в истории"
                )
        );
    }

    @Test
    void shouldRejectPaymentOfForeignOrder() {
        Long ownerId = testData.insertCustomer();
        Long strangerId = testData.insertCustomer();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(ownerId, OrderStatus.CREATED);

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> paymentService.payForOrder(strangerId, order.orderId(), cardPayment())
        );
    }

    @Test
    void shouldRejectSecondPaymentOfAlreadyPaidOrder() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        paymentService.payForOrder(customerId, order.orderId(), cardPayment());

        // Другой ключ идемпотентности — это уже новая попытка оплаты, и она обязана
        // упереться в статус заказа.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> paymentService.payForOrder(customerId, order.orderId(), cardPayment())
        );
    }

    private PayOrderRequest cardPayment() {
        return new PayOrderRequest(PaymentMethod.CARD, UUID.randomUUID().toString());
    }
}
