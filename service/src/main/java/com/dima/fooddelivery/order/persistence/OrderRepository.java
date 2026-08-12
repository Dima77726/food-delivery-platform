package com.dima.fooddelivery.order.persistence;

import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderItem;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Доступ к таблицам {@code customer_order} и {@code customer_order_item}.
 *
 * <p>Три правила, которым следует этот класс и все остальные репозитории проекта:
 * <ul>
 *   <li>наружу не отдаются типы из пакета {@code api} — иначе изменение контракта HTTP
 *       потянуло бы за собой правку SQL;</li>
 *   <li>бизнес-исключения не бросаются: репозиторий возвращает количество затронутых строк,
 *       а решение «ноль строк — это конфликт, отдаём 409» принимает сервис, он один знает
 *       контекст вызова;</li>
 *   <li>{@code null} как «не найдено» не возвращается — только {@link Optional}.</li>
 * </ul>
 *
 * <p>Транзакциями репозиторий не управляет: границы задаёт сервис.
 */
@Repository
@RequiredArgsConstructor
public class OrderRepository {

    /**
     * Общая проекция заказа с позициями. LEFT JOIN нужен, чтобы заказ без позиций всё равно
     * вернул строку — иначе «заказ не найден» и «заказ пуст» стали бы неразличимы.
     */
    private static final String SELECT_ORDER_WITH_ITEMS = """
            SELECT
                co.id            AS order_id,
                co.cart_id,
                co.customer_id,
                co.restaurant_id,
                co.status        AS order_status,
                co.total_amount,
                co.created_at,
                co.updated_at,

                coi.id           AS order_item_id,
                coi.menu_item_id,
                coi.menu_item_name,
                coi.quantity,
                coi.price,
                coi.line_total
            FROM customer_order co
            LEFT JOIN customer_order_item coi ON coi.order_id = co.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<Order> findById(Long orderId) {
        List<OrderRow> rows = jdbc.query(
                SELECT_ORDER_WITH_ITEMS + " WHERE co.id = :orderId ORDER BY coi.id",
                new MapSqlParameterSource("orderId", orderId),
                OrderRowMappers.ORDER_WITH_ITEMS
        );

        return assemble(rows);
    }

    public Optional<Order> findByIdAndCustomerId(Long orderId, Long customerId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("customerId", customerId);

        List<OrderRow> rows = jdbc.query(
                SELECT_ORDER_WITH_ITEMS + " WHERE co.id = :orderId AND co.customer_id = :customerId ORDER BY coi.id",
                params,
                OrderRowMappers.ORDER_WITH_ITEMS
        );

        return assemble(rows);
    }

    /**
     * Владелец и текущий статус заказа — всё, что нужно сервису для проверки прав и перехода.
     */
    public Optional<OrderAccess> findAccess(Long orderId) {
        List<OrderAccess> found = jdbc.query(
                """
                        SELECT co.id, co.customer_id, co.restaurant_id, co.status, co.total_amount
                        FROM customer_order co
                        WHERE co.id = :orderId
                        """,
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new OrderAccess(
                        rs.getLong("id"),
                        rs.getLong("customer_id"),
                        rs.getLong("restaurant_id"),
                        OrderStatus.fromDbValue(rs.getString("status")),
                        rs.getBigDecimal("total_amount")
                )
        );

        return found.stream().findFirst();
    }

    /**
     * Общая проекция списка заказов. Постраничная выборка обязана иметь строгий порядок:
     * без ORDER BY по уникальному ключу база вправе вернуть одну и ту же строку на двух
     * соседних страницах, и клиент увидит дубли. Поэтому id вторым критерием — created_at
     * у двух заказов может совпасть до микросекунды.
     */
    private static final String SELECT_SUMMARIES = """
            SELECT
                co.id AS order_id,
                co.cart_id,
                co.customer_id,
                co.restaurant_id,
                co.status AS order_status,
                co.total_amount,
                co.created_at,
                COUNT(coi.id) AS items_count
            FROM customer_order co
            LEFT JOIN customer_order_item coi ON coi.order_id = co.id
            WHERE %s
            GROUP BY co.id, co.cart_id, co.customer_id, co.restaurant_id,
                     co.status, co.total_amount, co.created_at
            ORDER BY co.created_at DESC, co.id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final RowMapper<OrderSummary> ORDER_SUMMARY = (rs, rowNum) -> new OrderSummary(
            rs.getLong("order_id"),
            rs.getLong("cart_id"),
            rs.getLong("customer_id"),
            rs.getLong("restaurant_id"),
            OrderStatus.fromDbValue(rs.getString("order_status")),
            rs.getBigDecimal("total_amount"),
            rs.getLong("items_count"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public List<OrderSummary> findSummariesByCustomerId(Long customerId, int limit, int offset) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("limit", limit)
                .addValue("offset", offset);

        return jdbc.query(SELECT_SUMMARIES.formatted("co.customer_id = :customerId"), params, ORDER_SUMMARY);
    }

    public long countByCustomerId(Long customerId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE customer_id = :customerId",
                new MapSqlParameterSource("customerId", customerId),
                Long.class
        );

        return total == null ? 0L : total;
    }

    public List<OrderSummary> findSummariesByRestaurantId(
            Long restaurantId,
            OrderStatus status,
            int limit,
            int offset
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("status", status == null ? null : status.getDbValue())
                .addValue("limit", limit)
                .addValue("offset", offset);

        // CAST нужен, потому что для NULL-параметра PostgreSQL не может вывести тип
        // и отказывается сравнивать его с колонкой.
        String condition = "co.restaurant_id = :restaurantId "
                + "AND (CAST(:status AS VARCHAR) IS NULL OR co.status = :status)";

        return jdbc.query(SELECT_SUMMARIES.formatted(condition), params, ORDER_SUMMARY);
    }

    public long countByRestaurantId(Long restaurantId, OrderStatus status) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("status", status == null ? null : status.getDbValue());

        Long total = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM customer_order
                        WHERE restaurant_id = :restaurantId
                          AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                        """,
                params,
                Long.class
        );

        return total == null ? 0L : total;
    }

    public boolean existsForCustomer(Long orderId, Long customerId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("customerId", customerId);

        Boolean exists = jdbc.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM customer_order co
                            WHERE co.id = :orderId AND co.customer_id = :customerId
                        )
                        """,
                params,
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    public Long insert(Long cartId, Long customerId, Long restaurantId, OrderStatus status, BigDecimal totalAmount) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("cartId", cartId)
                .addValue("customerId", customerId)
                .addValue("restaurantId", restaurantId)
                .addValue("status", status.getDbValue())
                .addValue("totalAmount", totalAmount);

        return jdbc.queryForObject(
                """
                        INSERT INTO customer_order (cart_id, customer_id, restaurant_id, status, total_amount)
                        VALUES (:cartId, :customerId, :restaurantId, :status, :totalAmount)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int[] insertItems(Long orderId, List<NewOrderItem> items) {
        SqlParameterSource[] batch = items.stream()
                .map(item -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("menuItemId", item.menuItemId())
                        .addValue("menuItemName", item.menuItemName())
                        .addValue("quantity", item.quantity())
                        .addValue("price", item.price())
                        .addValue("lineTotal", item.lineTotal()))
                .toArray(SqlParameterSource[]::new);

        return jdbc.batchUpdate(
                """
                        INSERT INTO customer_order_item (
                            order_id, menu_item_id, menu_item_name, quantity, price, line_total
                        )
                        VALUES (:orderId, :menuItemId, :menuItemName, :quantity, :price, :lineTotal)
                        """,
                batch
        );
    }

    /**
     * Атомарная смена статуса: тот же приём, что {@code AtomicInteger.compareAndSet}, только на
     * строке в базе. Условие {@code status = :expected} внутри UPDATE — это оптимистическая
     * блокировка: если между чтением и записью кто-то успел перевести заказ, обновится ноль строк
     * и вызывающий об этом узнает.
     *
     * <p>Проверки владельца здесь намеренно нет: «чужой заказ» и «заказ уже уехал» — разные
     * ошибки с разными HTTP-кодами, и различать их должен сервис.
     *
     * @return количество обновлённых строк: 1 — переход состоялся, 0 — статус успел измениться
     */
    public int compareAndSetStatus(Long orderId, OrderStatus expected, OrderStatus next) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("expected", expected.getDbValue())
                .addValue("next", next.getDbValue());

        return jdbc.update(
                """
                        UPDATE customer_order
                        SET status = :next,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :orderId
                          AND status = :expected
                        """,
                params
        );
    }

    private Optional<Order> assemble(List<OrderRow> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        OrderRow first = rows.get(0);

        Map<Long, OrderItem> items = new LinkedHashMap<>();
        for (OrderRow row : rows) {
            if (row.orderItemId() != null) {
                items.putIfAbsent(row.orderItemId(), new OrderItem(
                        row.orderItemId(),
                        row.menuItemId(),
                        row.menuItemName(),
                        row.quantity(),
                        row.price(),
                        row.lineTotal()
                ));
            }
        }

        return Optional.of(new Order(
                first.orderId(),
                first.cartId(),
                first.customerId(),
                first.restaurantId(),
                first.orderStatus(),
                first.totalAmount(),
                first.createdAt(),
                first.updatedAt(),
                new ArrayList<>(items.values())
        ));
    }

    /**
     * Позиция, которую ещё предстоит вставить: у неё нет идентификатора, в отличие от
     * {@link OrderItem}. Разные типы для «до записи» и «после записи» не дают случайно
     * обратиться к {@code id}, которого пока нет.
     */
    public record NewOrderItem(
            Long menuItemId,
            String menuItemName,
            Integer quantity,
            BigDecimal price,
            BigDecimal lineTotal
    ) {
    }
}
