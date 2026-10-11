package com.dima.fooddelivery.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Заказ глазами монолита — то, что отдаёт по gRPC сервис заказов.
 *
 * <p>Заказы уехали в order-service, а читать их по-прежнему нужно трём модулям: отзывам,
 * рекомендациям и треку курьера. Статус — строкой, как в контракте: монолит сравнивает его
 * с парой известных значений и не должен падать на новом статусе, о котором ещё не знает.
 *
 * @param delivery доставка заказа; {@code null}, пока её не создали
 */
public record Order(
        Long id,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        List<OrderItem> items,
        Delivery delivery
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
