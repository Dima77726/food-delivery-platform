package com.dima.fooddelivery.analytics.service;

import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.domain.StatusCount;
import com.dima.fooddelivery.analytics.persistence.OrderAnalyticsRepository;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Модуль Analytics. Отчёты по витрине заказов.
 *
 * <p>Сервис только читает: пишет в витрину потребитель Kafka, и другого пути данных сюда нет.
 * Это принципиально — витрину нельзя править из API, иначе она перестанет быть повторяемой
 * функцией от топика, и восстановить её перечитыванием станет нельзя.
 *
 * <p><b>Границы периода считаются в UTC.</b> День в отчёте — это {@code toDate(occurred_at)}
 * по колонке, объявленной в UTC. Если бы границы диапазона считались в поясе приложения,
 * заказ, оформленный в 02:00 по Москве, попадал бы в выборку за один день, а в группировку —
 * за предыдущий. Один пояс на всю цепочку убирает этот класс расхождений целиком.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AnalyticsService {

    /**
     * Больше года за один запрос не отдаём. Предел не про размер ответа — 366 строк
     * не проблема, — а про объём чтения: диапазон в десять лет заставит ClickHouse читать
     * все партиции и займёт единственное соединение из маленького пула.
     */
    private static final int MAX_PERIOD_DAYS = 366;

    private final OrderAnalyticsRepository orderAnalyticsRepository;

    /**
     * Продажи ресторана по дням.
     *
     * @param restaurantId ресторан или {@code null} для всей платформы
     */
    public List<DailySales> dailySales(Long restaurantId, LocalDate from, LocalDate to) {
        requireValidPeriod(from, to);

        return orderAnalyticsRepository.dailySales(
                restaurantId,
                OrderStatus.PAID.getDbValue(),
                startOfDay(from),
                startOfDay(to.plusDays(1))
        );
    }

    /**
     * Воронка статусов за период.
     *
     * @param restaurantId ресторан или {@code null} для всей платформы
     */
    public List<StatusCount> statusFunnel(Long restaurantId, LocalDate from, LocalDate to) {
        requireValidPeriod(from, to);

        return orderAnalyticsRepository.statusFunnel(
                restaurantId,
                startOfDay(from),
                startOfDay(to.plusDays(1))
        );
    }

    private static void requireValidPeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessRuleViolationException("Начало периода позже его конца");
        }

        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new BusinessRuleViolationException(
                    "Период длиннее " + MAX_PERIOD_DAYS + " дней запрашивать нельзя"
            );
        }
    }

    /**
     * Верхняя граница диапазона всегда исключающая: {@code to} превращается в полночь
     * следующего дня. Иначе последний день периода терялся бы наполовину — в него попали бы
     * только заказы, оформленные ровно в 00:00:00.
     */
    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
