package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.Order;
import com.dima.fooddelivery.order.OrderClient;
import com.dima.fooddelivery.order.OrderItem;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Сервис заказов в памяти — вместо gRPC-клиента к order-service.
 *
 * <p>Заказов в базе монолита больше нет: отзывы и рекомендации спрашивают их у сервиса
 * заказов через {@link OrderClient}. Тестам этих модулей нужен ответ, а не живой второй
 * сервис, поэтому {@link TestDataFactory} заводит заказы здесь, а модули читают отсюда же —
 * через тот же интерфейс, что и в бою. Сам gRPC-клиент проверяется отдельно, против сервера
 * в памяти процесса.
 *
 * <p>Контекст Spring общий для тестов, а с ним и этот объект: изоляцию дают идентификаторы,
 * которые никогда не повторяются.
 */
public class TestOrders implements OrderClient {

    private final Map<Long, Order> orders = new ConcurrentSkipListMap<>();

    public void addOrder(Long orderId, Long customerId, Long restaurantId, String status, BigDecimal totalAmount) {
        orders.put(orderId, new Order(
                orderId, customerId, restaurantId, status, totalAmount, OffsetDateTime.now(), List.of()
        ));
    }

    public void addItem(Long orderId, Long menuItemId, String name, int quantity, BigDecimal price) {
        orders.computeIfPresent(orderId, (id, order) -> {
            List<OrderItem> items = new ArrayList<>(order.items());
            items.add(new OrderItem(menuItemId, name, quantity, price));

            return copy(order, order.status(), items);
        });
    }

    /** Смена статуса «в сервисе заказов» — например, отмена посреди прохода проекции. */
    public void changeStatus(Long orderId, String status) {
        orders.computeIfPresent(orderId, (id, order) -> copy(order, status, order.items()));
    }

    @Override
    public Order requireOrder(Long orderId) {
        Order order = orders.get(orderId);

        if (order == null) {
            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
        }

        return order;
    }

    @Override
    public List<Order> findAfter(long afterOrderId, int limit) {
        return orders.values().stream()
                .filter(order -> order.id() > afterOrderId)
                .sorted(Comparator.comparing(Order::id))
                .limit(limit)
                .toList();
    }

    private static Order copy(Order order, String status, List<OrderItem> items) {
        return new Order(
                order.id(),
                order.customerId(),
                order.restaurantId(),
                status,
                order.totalAmount(),
                order.createdAt(),
                List.copyOf(items)
        );
    }
}
