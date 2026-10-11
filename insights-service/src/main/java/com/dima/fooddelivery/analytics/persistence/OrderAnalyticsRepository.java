package com.dima.fooddelivery.analytics.persistence;

import com.dima.fooddelivery.analytics.domain.AnalyticsEvent;
import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.domain.StatusCount;
import com.dima.fooddelivery.common.stores.ClickHouseJdbc;
import com.dima.fooddelivery.common.stores.StoreToggles;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Витрина заказов в ClickHouse. SQL написан руками, как и в модуле order.
 *
 * <p>Синтаксис похож на PostgreSQL, но привычки другие. Три из них видны прямо здесь.
 *
 * <p><b>Вставка идёт пачками.</b> Одиночный INSERT для ClickHouse — антипаттерн: каждая
 * вставка создаёт на диске отдельный кусок данных, который потом придётся сливать. Тысяча
 * вставок по строке дают тысячу кусков и заметную нагрузку на фоновое слияние, тогда как
 * одна вставка на тысячу строк даёт один кусок. Поэтому потребитель Kafka в этом модуле
 * пакетный, и сюда приходит список, а не событие.
 *
 * <p><b>Запросы устойчивы к дубликатам.</b> {@code count()} посчитал бы повторно доставленное
 * событие дважды, поэтому его здесь нет: заказы считаются через {@code uniqExact}, а суммы —
 * по предварительно раздублированной выборке. Почему дубликаты в таблице вообще возможны,
 * написано в {@link OrderAnalyticsSchema}.
 *
 * <p><b>Время передаётся строкой в UTC.</b> Колонка объявлена как DateTime64(3) с поясом UTC,
 * и строка разбирается сервером именно в этом поясе. Через {@code setTimestamp} значение
 * прошло бы через календарь JVM, и результат зависел бы от часового пояса машины —
 * ровно то, чего в аналитике быть не должно.
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class OrderAnalyticsRepository {

    /** Формат, который ClickHouse принимает для DateTime64(3). */
    private static final DateTimeFormatter CLICKHOUSE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final String INSERT = """
            INSERT INTO %s (
                event_id, event_type, order_id, customer_id, restaurant_id,
                previous_status, new_status, total_amount, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(OrderAnalyticsSchema.TABLE);

    /**
     * Продажи по дням.
     *
     * <p>Вложенный SELECT DISTINCT — не украшение и не лень. Сумму выручки нельзя считать
     * прямо по таблице: повторно доставленное событие удвоило бы её. DISTINCT по тройке
     * (заказ, сумма, день) оставляет от любого числа копий одного события ровно одну строку,
     * и только после этого считается сумма.
     *
     * <p>Берётся переход в статус PAID, а не в DELIVERED: выручка признаётся в момент оплаты,
     * и отменённый после оплаты заказ должен попадать в отчёт наравне с доставленным —
     * иначе цифры перестают сходиться с деньгами.
     */
    private static final String DAILY_SALES = """
            SELECT
                toString(day)     AS day,
                count()           AS orders,
                sum(total_amount) AS revenue
            FROM (
                SELECT DISTINCT
                    order_id,
                    total_amount,
                    toDate(occurred_at) AS day
                FROM %s
                WHERE new_status = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                  %%s
            )
            GROUP BY day
            ORDER BY day
            """.formatted(OrderAnalyticsSchema.TABLE);

    /**
     * Воронка статусов: сколько разных заказов побывало в каждом статусе за период.
     *
     * <p>{@code uniqExact}, а не {@code count()}: нужны именно разные заказы, и дубликаты
     * событий не должны на это влиять. Приблизительный {@code uniq} был бы дешевле,
     * но на витрине отчётности расхождение в единицы заказов выглядит как ошибка в данных.
     */
    private static final String STATUS_FUNNEL = """
            SELECT
                new_status          AS status,
                uniqExact(order_id) AS orders
            FROM %s
            WHERE occurred_at >= ?
              AND occurred_at < ?
              %%s
            GROUP BY status
            ORDER BY orders DESC, status
            """.formatted(OrderAnalyticsSchema.TABLE);

    private static final RowMapper<DailySales> DAILY_SALES_MAPPER = (rs, rowNum) -> {
        long orders = rs.getLong("orders");
        BigDecimal revenue = rs.getBigDecimal("revenue");

        return new DailySales(
                // День приходит строкой вида 2026-09-08: так он не зависит ни от календаря JVM,
                // ни от того, во что драйвер решит превратить тип Date.
                LocalDate.parse(rs.getString("day")),
                orders,
                revenue,
                orders == 0
                        ? BigDecimal.ZERO
                        : revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP)
        );
    };

    private static final RowMapper<StatusCount> STATUS_COUNT_MAPPER =
            (rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("orders"));

    private final ClickHouseJdbc clickHouse;

    /**
     * Пакетная вставка событий.
     *
     * @return сколько строк ушло в ClickHouse
     */
    public int insertBatch(List<AnalyticsEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }

        clickHouse.template().batchUpdate(INSERT, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                AnalyticsEvent event = events.get(index);

                // UUID передаётся строкой: тип UUID в ClickHouse её принимает,
                // а setObject с java.util.UUID драйверы понимают по-разному.
                statement.setString(1, event.eventId().toString());
                statement.setString(2, event.eventType());
                statement.setLong(3, event.orderId());
                statement.setLong(4, event.customerId());
                statement.setLong(5, event.restaurantId());
                statement.setString(6, event.previousStatus() == null ? "" : event.previousStatus());
                statement.setString(7, event.newStatus());
                statement.setBigDecimal(8, event.totalAmount() == null ? BigDecimal.ZERO : event.totalAmount());
                statement.setString(9, CLICKHOUSE_TIMESTAMP.format(event.occurredAt()));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        return events.size();
    }

    /**
     * Продажи по дням за период.
     *
     * @param restaurantId ресторан или {@code null} для всей платформы
     * @param paidStatus   статус, который считается моментом оплаты
     */
    public List<DailySales> dailySales(Long restaurantId, String paidStatus, Instant from, Instant to) {
        List<Object> args = new ArrayList<>(4);
        args.add(paidStatus);
        args.add(CLICKHOUSE_TIMESTAMP.format(from));
        args.add(CLICKHOUSE_TIMESTAMP.format(to));

        if (restaurantId != null) {
            args.add(restaurantId);
        }

        return clickHouse.template().query(
                DAILY_SALES.formatted(restaurantFilter(restaurantId)),
                DAILY_SALES_MAPPER,
                args.toArray()
        );
    }

    public List<StatusCount> statusFunnel(Long restaurantId, Instant from, Instant to) {
        List<Object> args = new ArrayList<>(3);
        args.add(CLICKHOUSE_TIMESTAMP.format(from));
        args.add(CLICKHOUSE_TIMESTAMP.format(to));

        if (restaurantId != null) {
            args.add(restaurantId);
        }

        return clickHouse.template().query(
                STATUS_FUNNEL.formatted(restaurantFilter(restaurantId)),
                STATUS_COUNT_MAPPER,
                args.toArray()
        );
    }

    /** Сколько строк лежит в витрине. Нужен наблюдаемости и тестам, в отчётах не участвует. */
    public long countEvents() {
        Long count = clickHouse.template().queryForObject(
                "SELECT count() FROM " + OrderAnalyticsSchema.TABLE,
                Long.class
        );

        return count == null ? 0L : count;
    }

    /**
     * Фильтр по ресторану — фрагмент SQL, а не параметр. Строка здесь постоянная и не зависит
     * от пользовательского ввода: подставляется либо пустота, либо ровно этот текст.
     * Идентификатор ресторана при этом остаётся обычным параметром запроса.
     */
    private static String restaurantFilter(Long restaurantId) {
        return restaurantId == null ? "" : "AND restaurant_id = ?";
    }
}
