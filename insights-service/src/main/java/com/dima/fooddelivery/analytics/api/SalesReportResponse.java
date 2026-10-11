package com.dima.fooddelivery.analytics.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Отчёт по продажам за период.
 *
 * <p>Итоги считаются здесь, а не запросом в ClickHouse: сумма по десятку строк, уже
 * приехавших в память, не стоит второго обращения к базе. Отдельный запрос понадобился бы,
 * если бы дни не помещались в ответ целиком, — но такой отчёт и не пролез бы в ограничение
 * на длину периода.
 *
 * @param restaurantId ресторан или {@code null}, если отчёт по всей платформе
 */
public record SalesReportResponse(
        Long restaurantId,
        LocalDate from,
        LocalDate to,
        long totalOrders,
        BigDecimal totalRevenue,
        List<DailySalesResponse> days
) {

    public record DailySalesResponse(
            LocalDate day,
            long orders,
            BigDecimal revenue,
            BigDecimal averageCheck
    ) {
    }
}
