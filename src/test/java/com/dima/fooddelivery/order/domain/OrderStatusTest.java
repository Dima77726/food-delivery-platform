package com.dima.fooddelivery.order.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    @Test
    void shouldResolveStatusFromDatabaseValue() {
        assertSame(OrderStatus.CREATED, OrderStatus.fromDbValue("CREATED"));
        assertSame(OrderStatus.READY_FOR_DELIVERY, OrderStatus.fromDbValue("READY_FOR_DELIVERY"));
    }

    @Test
    void shouldRejectUnknownDatabaseValue() {
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromDbValue("UNKNOWN"));
    }

    @Test
    void shouldAllowOnlyValidTransitions() {
        assertTrue(OrderStatus.CREATED.canBeCanceledByCustomer());
        assertTrue(OrderStatus.CREATED.canBeAcceptedByRestaurant());
        assertTrue(OrderStatus.ACCEPTED.canStartCookingByRestaurant());
        assertTrue(OrderStatus.COOKING.canBeMarkedReadyForDeliveryByRestaurant());
        assertTrue(OrderStatus.READY_FOR_DELIVERY.canCreateDelivery());
        assertTrue(OrderStatus.READY_FOR_DELIVERY.canBeMovedToInDeliveryByCourier());
        assertTrue(OrderStatus.IN_DELIVERY.canBeCompletedByCourier());

        assertFalse(OrderStatus.ACCEPTED.canBeCanceledByCustomer());
        assertFalse(OrderStatus.COOKING.canBeAcceptedByRestaurant());
        assertFalse(OrderStatus.CREATED.canCreateDelivery());
        assertFalse(OrderStatus.DELIVERED.canBeCompletedByCourier());
    }
}
