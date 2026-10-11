package com.dima.fooddelivery.support;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Собирает состояние для интеграционных тестов монолита.
 *
 * <p>В базе монолита не осталось ни пользователей, ни ресторанов, ни заказов: они живут
 * в identity-service, catalog-service и order-service. Их заменяют генераторы идентификаторов,
 * {@link TestRestaurantAccess} и {@link TestOrders} — заказы «в сервисе заказов» заводятся там.
 */
public class TestDataFactory {

    /**
     * Идентификаторы — из одного растущего счётчика, начатого со времени запуска. Контекст
     * Spring общий для тестов, а хранилища вроде Neo4j и MongoDB откатом не очищаются:
     * уникальность даёт только то, что номера никогда не повторяются.
     */
    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 2_000_000_000L);

    private final TestOrders orders;
    private final TestRestaurantAccess restaurantAccess;

    public TestDataFactory(TestOrders orders, TestRestaurantAccess restaurantAccess) {
        this.orders = orders;
        this.restaurantAccess = restaurantAccess;
    }

    public long uniqueSuffix() {
        return IDS.incrementAndGet();
    }

    public Long insertCustomer() {
        return IDS.incrementAndGet();
    }

    public Long insertCourier() {
        return IDS.incrementAndGet();
    }

    /** Управляющий ресторана — в тестовых правах вместо identity-service. */
    public void assignRestaurantManager(Long userId, Long restaurantId) {
        restaurantAccess.assignManager(userId, restaurantId);
    }

    public Long insertRestaurant() {
        return IDS.incrementAndGet();
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price) {
        return IDS.incrementAndGet();
    }

    public Long insertOrder(Long customerId, Long restaurantId, String status, BigDecimal totalAmount) {
        Long orderId = IDS.incrementAndGet();
        orders.addOrder(orderId, customerId, restaurantId, status, totalAmount);

        return orderId;
    }

    public void insertOrderItem(Long orderId, Long menuItemId, int quantity, BigDecimal price) {
        orders.addItem(orderId, menuItemId, "Test Item " + menuItemId, quantity, price);
    }

    public void changeOrderStatus(Long orderId, String status) {
        orders.changeStatus(orderId, status);
    }

    /** Заказ с одной позицией в новом ресторане. */
    public OrderContext insertOrderInStatus(Long customerId, String status) {
        BigDecimal price = new BigDecimal("450.00");

        Long restaurantId = insertRestaurant();
        Long menuItemId = insertMenuItem(restaurantId, price);
        Long orderId = insertOrder(customerId, restaurantId, status, price);
        insertOrderItem(orderId, menuItemId, 1, price);

        return new OrderContext(restaurantId, menuItemId, orderId, price);
    }

    public record OrderContext(
            Long restaurantId,
            Long menuItemId,
            Long orderId,
            BigDecimal price
    ) {
    }
}
