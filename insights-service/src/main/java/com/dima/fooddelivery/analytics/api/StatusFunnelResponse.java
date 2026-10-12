package com.dima.fooddelivery.analytics.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Воронка статусов за период.
 *
 * <p>Читается сверху вниз: сколько заказов дошло до оплаты, сколько ресторан принял,
 * сколько доехало. Разрыв между соседними ступенями и есть ответ на вопрос, где теряются
 * заказы, — ради него отчёт и нужен.
 *
 * @param restaurantId ресторан или {@code null}, если отчёт по всей платформе
 */
public record StatusFunnelResponse(
        Long restaurantId,
        LocalDate from,
        LocalDate to,
        List<StatusCountResponse> statuses
) {

    public record StatusCountResponse(
            String status,
            long orders
    ) {
    }
}
