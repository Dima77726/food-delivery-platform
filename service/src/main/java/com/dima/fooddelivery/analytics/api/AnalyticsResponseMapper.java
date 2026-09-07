package com.dima.fooddelivery.analytics.api;

import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.domain.StatusCount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AnalyticsResponseMapper {

    public static SalesReportResponse toSalesReport(
            Long restaurantId,
            LocalDate from,
            LocalDate to,
            List<DailySales> days
    ) {
        long totalOrders = days.stream().mapToLong(DailySales::orders).sum();

        BigDecimal totalRevenue = days.stream()
                .map(DailySales::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SalesReportResponse(
                restaurantId,
                from,
                to,
                totalOrders,
                totalRevenue,
                days.stream()
                        .map(day -> new SalesReportResponse.DailySalesResponse(
                                day.day(),
                                day.orders(),
                                day.revenue(),
                                day.averageCheck()
                        ))
                        .toList()
        );
    }

    public static StatusFunnelResponse toFunnel(
            Long restaurantId,
            LocalDate from,
            LocalDate to,
            List<StatusCount> statuses
    ) {
        return new StatusFunnelResponse(
                restaurantId,
                from,
                to,
                statuses.stream()
                        .map(status -> new StatusFunnelResponse.StatusCountResponse(
                                status.status(),
                                status.orders()
                        ))
                        .toList()
        );
    }

    private AnalyticsResponseMapper() {
    }
}
