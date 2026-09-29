package com.dima.fooddelivery.analytics.api;

import com.dima.fooddelivery.analytics.service.AnalyticsService;
import com.dima.fooddelivery.common.stores.StoreToggles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Отчёты по витрине заказов.
 *
 * <p>Три маршрута и два уровня доступа. Ресторан видит только себя — за этим следит
 * {@code @access.managesRestaurant}. Отчёт по всей платформе живёт под префиксом
 * {@code /api/v1/admin}, который закрыт ролью ADMIN ещё в цепочке фильтров; дублирующий
 * {@code @PreAuthorize} стоит по той же причине, что и в {@code AdminController}.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
@Tag(name = "Analytics", description = "Отчёты по заказам (ClickHouse)")
@SecurityRequirement(name = "bearer-jwt")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/api/v1/restaurants/{restaurantId}/analytics/sales")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Продажи ресторана по дням")
    public SalesReportResponse getRestaurantSales(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return AnalyticsResponseMapper.toSalesReport(
                restaurantId,
                from,
                to,
                analyticsService.dailySales(restaurantId, from, to)
        );
    }

    @GetMapping("/api/v1/restaurants/{restaurantId}/analytics/funnel")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Воронка статусов заказов ресторана")
    public StatusFunnelResponse getRestaurantFunnel(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return AnalyticsResponseMapper.toFunnel(
                restaurantId,
                from,
                to,
                analyticsService.statusFunnel(restaurantId, from, to)
        );
    }

    @GetMapping("/api/v1/admin/analytics/sales")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Продажи всей платформы по дням")
    public SalesReportResponse getPlatformSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return AnalyticsResponseMapper.toSalesReport(
                null,
                from,
                to,
                analyticsService.dailySales(null, from, to)
        );
    }
}
