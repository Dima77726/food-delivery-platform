package com.dima.fooddelivery.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Заказ глазами insights-service — то, что отдаёт по gRPC сервис заказов.
 *
 * <p>Заказы уехали в order-service, а читать их по-прежнему нужно двум модулям: отзывам
 * и рекомендациям. Статус — строкой, как в контракте: сервис сравнивает его
 * с парой известных значений и не должен падать на новом статусе, о котором ещё не знает.
 */
public record Order(
        Long id,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        List<OrderItem> items
) {

    public static final String DELIVERED = "DELIVERED";

    public static final String CANCELED = "CANCELED";

    public boolean belongsToCustomer(Long candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    public boolean isDelivered() {
        return DELIVERED.equals(status);
    }

    public boolean isCanceled() {
        return CANCELED.equals(status);
    }
}
