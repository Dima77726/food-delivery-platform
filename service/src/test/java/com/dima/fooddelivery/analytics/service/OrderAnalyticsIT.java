package com.dima.fooddelivery.analytics.service;

import com.dima.fooddelivery.analytics.domain.AnalyticsEvent;
import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.domain.StatusCount;
import com.dima.fooddelivery.analytics.persistence.OrderAnalyticsRepository;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractStoresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Витрина аналитики на настоящем ClickHouse.
 *
 * <p>Главная проверка здесь — устойчивость к дубликатам. Kafka доставляет «хотя бы один раз»,
 * а ReplacingMergeTree схлопывает копии только при фоновом слиянии, которое может не случиться
 * ни сегодня, ни завтра. Значит, правильность отчёта обеспечивают сами запросы, и проверить
 * это можно только на настоящем ClickHouse: ни один мок не воспроизведёт движок таблицы.
 *
 * <p>События вставляются напрямую через репозиторий, минуя Kafka. Так тест проверяет то,
 * что хочет проверить - SQL витрины, — и не превращается в ожидание доставки сообщения
 * с непредсказуемым таймаутом.
 */
class OrderAnalyticsIT extends AbstractStoresIntegrationTest {

    /** Фиксированный день, чтобы отчёт не зависел от даты запуска тестов. */
    private static final LocalDate DAY = LocalDate.of(2026, 1, 15);
    private static final Instant NOON = Instant.parse("2026-01-15T12:00:00Z");

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private OrderAnalyticsRepository orderAnalyticsRepository;

    @Test
    void shouldCountDailySalesAndAverageCheck() {
        Long restaurantId = testData.insertRestaurant();

        orderAnalyticsRepository.insertBatch(List.of(
                paid(restaurantId, 1001L, "300.00"),
                paid(restaurantId, 1002L, "500.00")
        ));

        List<DailySales> sales = analyticsService.dailySales(restaurantId, DAY, DAY);

        assertAll(
                () -> assertEquals(1, sales.size(), "оба заказа оплачены в один день"),
                () -> assertEquals(DAY, sales.get(0).day()),
                () -> assertEquals(2L, sales.get(0).orders()),
                () -> assertEquals(0, new BigDecimal("800.00").compareTo(sales.get(0).revenue())),
                () -> assertEquals(0, new BigDecimal("400.00").compareTo(sales.get(0).averageCheck()))
        );
    }

    /**
     * То же самое событие, доставленное дважды. В таблице после этого лежат две строки —
     * и отчёт обязан этого не заметить.
     */
    @Test
    void shouldIgnoreDuplicateEventsInRevenue() {
        Long restaurantId = testData.insertRestaurant();

        AnalyticsEvent event = paid(restaurantId, 2001L, "700.00");

        orderAnalyticsRepository.insertBatch(List.of(event));
        orderAnalyticsRepository.insertBatch(List.of(event));

        List<DailySales> sales = analyticsService.dailySales(restaurantId, DAY, DAY);

        assertAll(
                () -> assertEquals(1L, sales.get(0).orders(), "заказ один, сколько бы копий события ни пришло"),
                () -> assertEquals(0, new BigDecimal("700.00").compareTo(sales.get(0).revenue()))
        );
    }

    /**
     * Воронка: один заказ проходит несколько статусов и обязан попасть в каждую ступень
     * ровно один раз, даже если событие о переходе задвоилось.
     */
    @Test
    void shouldBuildStatusFunnel() {
        Long restaurantId = testData.insertRestaurant();

        AnalyticsEvent accepted = event(restaurantId, 3001L, OrderStatus.ACCEPTED, "400.00");

        orderAnalyticsRepository.insertBatch(List.of(
                paid(restaurantId, 3001L, "400.00"),
                accepted,
                accepted,
                paid(restaurantId, 3002L, "600.00")
        ));

        Map<String, Long> funnel = analyticsService.statusFunnel(restaurantId, DAY, DAY).stream()
                .collect(Collectors.toMap(StatusCount::status, StatusCount::orders));

        assertAll(
                () -> assertEquals(2L, funnel.get(OrderStatus.PAID.getDbValue()), "оплачено два заказа"),
                () -> assertEquals(1L, funnel.get(OrderStatus.ACCEPTED.getDbValue()), "принят один"),
                () -> assertEquals(2, funnel.size(), "других статусов в периоде не было")
        );
    }

    /** Витрина одного ресторана не должна показывать чужие заказы. */
    @Test
    void shouldSeparateRestaurants() {
        Long mine = testData.insertRestaurant();
        Long other = testData.insertRestaurant();

        orderAnalyticsRepository.insertBatch(List.of(
                paid(mine, 4001L, "100.00"),
                paid(other, 4002L, "900.00")
        ));

        List<DailySales> sales = analyticsService.dailySales(mine, DAY, DAY);

        assertAll(
                () -> assertEquals(1L, sales.get(0).orders()),
                () -> assertEquals(0, new BigDecimal("100.00").compareTo(sales.get(0).revenue()))
        );
    }

    @Test
    void shouldReturnNothingForPeriodWithoutSales() {
        List<DailySales> sales = analyticsService.dailySales(
                testData.insertRestaurant(), DAY, DAY.plusDays(3)
        );

        assertTrue(sales.isEmpty());
    }

    @Test
    void shouldRejectInvertedPeriod() {
        assertThrows(
                BusinessRuleViolationException.class,
                () -> analyticsService.dailySales(testData.insertRestaurant(), DAY, DAY.minusDays(1))
        );
    }

    @Test
    void shouldRejectPeriodLongerThanYear() {
        assertThrows(
                BusinessRuleViolationException.class,
                () -> analyticsService.dailySales(testData.insertRestaurant(), DAY, DAY.plusYears(2))
        );
    }

    private static AnalyticsEvent paid(Long restaurantId, long orderId, String amount) {
        return event(restaurantId, orderId, OrderStatus.PAID, amount);
    }

    /**
     * Идентификатор события выводится из заказа и статуса, а не случайный: две копии одного
     * события обязаны быть неотличимы, иначе проверка на дубликаты ничего бы не проверяла.
     */
    private static AnalyticsEvent event(Long restaurantId, long orderId, OrderStatus status, String amount) {
        return new AnalyticsEvent(
                UUID.nameUUIDFromBytes((restaurantId + ":" + orderId + ":" + status).getBytes()),
                "ORDER_" + status.getDbValue(),
                orderId,
                1L,
                restaurantId,
                null,
                status.getDbValue(),
                new BigDecimal(amount),
                NOON
        );
    }

}
