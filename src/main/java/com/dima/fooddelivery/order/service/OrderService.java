package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.api.OrderEventResponse;
import com.dima.fooddelivery.order.api.OrderItemResponse;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.api.OrderSummaryResponse;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.OrderRow;
import com.dima.fooddelivery.order.persistence.OrderRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRowMapper orderRowMapper;

    @Transactional
    public OrderResponse createOrderFromActiveCart(Long customerId, Long restaurantId) {
        log.info(
                "Начинаем создание заказа из активной корзины: customerId={}, restaurantId={}",
                customerId,
                restaurantId
        );

        Long cartId = findActiveCartId(customerId, restaurantId);

        if (cartId == null) {
            log.warn(
                    "Активная корзина не найдена при создании заказа: customerId={}, restaurantId={}",
                    customerId,
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Активная корзина не найдена для customerId=" + customerId + " и restaurantId=" + restaurantId
            );
        }

        List<CartItemForOrder> cartItems = findCartItemsForOrder(cartId);

        if (cartItems.isEmpty()) {
            log.warn("Нельзя создать заказ из пустой корзины: cartId={}", cartId);

            throw new BusinessRuleViolationException(
                    "Нельзя создать заказ из пустой корзины: cartId=" + cartId
            );
        }

        BigDecimal totalAmount = calculateTotalAmount(cartItems);

        Long orderId = createOrder(cartId, customerId, restaurantId, totalAmount);

        createOrderItems(orderId, cartItems);

        checkoutCart(cartId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_CREATED,
                "Заказ создан из активной корзины"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Заказ успешно создан из корзины: orderId={}, cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.cartId(),
                response.items().size(),
                response.totalAmount()
        );

        return response;

    }

    public OrderResponse getOrderByIdForCustomer(Long customerId, Long orderId) {
        log.info(
                "Получаем заказ клиента: customerId={}, orderId={}",
                customerId,
                orderId
        );

        String sql = """
                SELECT
                    co.id AS order_id,
                    co.cart_id,
                    co.customer_id,
                    co.restaurant_id,
                    co.status AS order_status,
                    co.total_amount,

                    coi.id AS order_item_id,
                    coi.menu_item_id,
                    coi.menu_item_name,
                    coi.quantity,
                    coi.price,
                    coi.line_total
                FROM food_app.customer_order co
                LEFT JOIN food_app.customer_order_item coi ON coi.order_id = co.id
                WHERE co.id = ?
                and co.customer_id = ?
                ORDER BY coi.id 
                """;

        List<OrderRow> rows = jdbcTemplate.query(sql, orderRowMapper, orderId, customerId);

        if (rows.isEmpty()) {
            log.warn(
                    "Заказ клиента не найден: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException(
                    "Заказ с id=" + orderId + " не найден для customerId=" + customerId
            );
        }

        OrderResponse response = buildOrderResponse(rows);

        log.info(
                "Заказ клиента загружен: customerId={}, orderId={}, status={}, totalAmount={}, itemsCount={}",
                response.customerId(),
                response.id(),
                response.status(),
                response.totalAmount(),
                response.items().size()
                );

        return response;
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrdersByCustomer(Long customerId) {
        log.info("Получаем список заказов клиента: customerId={}", customerId);

        String sql = """
                SELECT
                    co.id AS order_id,
                    co.cart_id,
                    co.customer_id,
                    co.restaurant_id,
                    co.status AS order_status,
                    co.total_amount,
                    co.created_at,
                    COUNT(coi.id) AS items_count
                FROM food_app.customer_order co
                LEFT JOIN food_app.customer_order_item coi ON coi.order_id = co.id
                WHERE co.customer_id = ?
                GROUP BY co.id,
                         co.cart_id,
                         co.customer_id,
                         co.restaurant_id,
                         co.status,
                         co.total_amount,
                         co.created_at
                ORDER BY co.created_at  DESC, co.id DESC
                """;

        List<OrderSummaryResponse> orders = jdbcTemplate.query(sql,
                (rs, rowNum) -> new OrderSummaryResponse(
                        rs.getLong("order_id"),
                        rs.getLong("cart_id"),
                        rs.getLong("customer_id"),
                        rs.getLong("restaurant_id"),
                        rs.getString("order_status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getObject("items_count", Long.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)
                ),
                customerId
        );
        log.info(
                "Список заказов клиента загружен: customerId={}, ordersCount={}",
                customerId,
                orders.size()
        );

        return orders;
    }

    @Transactional
    public OrderResponse cancelOrder(Long customerId, Long orderId) {
        log.info(
                "Начинаем отмену заказа: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForCustomer(customerId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Заказ не найден при отмене: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден для customerId=" + customerId);
        }

        if (!orderStatusSnapshot.status().canBeCanceledByCustomer()) {
            log.warn(
                    "Заказ нельзя отменить в текущем статусе: customerId={}, orderId={}, currentStatus={}",
                    customerId,
                    orderId,
                    orderStatusSnapshot.status()
            );

            throw new BusinessRuleViolationException(
                    "Отменить можно только заказ в статусе CREATED. Текущий статус="
                            + orderStatusSnapshot.status().getDbValue()
            );
        }

        updateOrderStatusToCanceled(customerId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_CANCELED,
                "Заказ отменён клиентом"
        );

        OrderResponse response = getOrderByIdForCustomer(customerId, orderId);

        log.info(
                "Заказ успешно отменён: customerId={}, orderId={}, newStatus={}",
                customerId,
                orderId,
                response.status()
        );

        return response;
    }

    @Transactional(readOnly = true)
    public List<OrderEventResponse> getOrderEventsForCustomer(Long customerId, Long orderId) {
        log.info(
                "Получаем историю событий заказа: customerId={}, orderId={}",
                customerId,
                orderId
        );

        boolean orderExist = existsOrderForCustomer(customerId, orderId);

        if (!orderExist) {
            log.warn(
                    "Заказ не найден при получении истории событий: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден для customerId=" + customerId);
        }

        String sql = """
                SELECT 
                    e.id,
                    e.order_id,
                    e.event_type,
                    e.description,
                    e.created_at
                FROM food_app.customer_order_event e
                where e.order_id = ?
                order by e.created_at ASC , e.id ASC 
                """;

        List<OrderEventResponse> events = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OrderEventResponse(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getString("event_type"),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                orderId
        );

        log.info(
                "История событий заказа загружена: customerId={}, orderId={}, eventsCount={}",
                customerId,
                orderId,
                events.size()
        );

        return events;
    }

    @Transactional
    public OrderResponse acceptOrder(Long restaurantId, Long orderId) {
        log.info(
                "Начинаем принятие заказа рестораном: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForRestaurant(restaurantId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Заказ не найден при принятии рестораном: restaurantId={}, orderId={}",
                    restaurantId,
                    orderId
            );

            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден для restaurantId=" + restaurantId);
        }

        if (!orderStatusSnapshot.status().canBeAcceptedByRestaurant()) {
            log.warn(
                    "Заказ нельзя принять в текущем статусе: restaurantId={}, orderId={}, currentStatus={}",
                    restaurantId,
                    orderId,
                    orderStatusSnapshot.status().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Принять можно только заказ в статусе CREATED. Текущий статус="
                            + orderStatusSnapshot.status().getDbValue()
            );
        }

        updateOrderStatusToAccepted(restaurantId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_ACCEPTED,
                "Заказ принят рестораном"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Заказ принят рестораном: restaurantId={}, orderId={}, newStatus={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }

    @Transactional
    public OrderResponse startCookingOrder(Long restaurantId, Long orderId) {
        log.info(
                "Начинаем готовку заказа: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForRestaurant(restaurantId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Заказ не найден при начале готовки: restaurantId={}, orderId={}",
                    restaurantId,
                    orderId
            );

            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден для restaurantId=" + restaurantId);
        }

        if (!orderStatusSnapshot.status().canStartCookingByRestaurant()) {
            log.warn(
                    "Нельзя начать готовку заказа в текущем статусе: restaurantId={}, orderId={}, currentStatus={}",
                    restaurantId,
                    orderId,
                    orderStatusSnapshot.status().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Начать готовку можно только для заказа в статусе ACCEPTED. Текущий статус="
                            + orderStatusSnapshot.status().getDbValue()
            );
        }

        updateOrderStatusToCooking(restaurantId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_COOKING_STARTED,
                "Ресторан начал готовить заказ"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Готовка заказа начата: restaurantId={}, orderId={}, newStatus={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }

    @Transactional
    public OrderResponse markOrderReadyForDelivery(Long restaurantId, Long orderId) {
        log.info(
                "Отмечаем заказ готовым к доставке: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForRestaurant(restaurantId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Заказ не найден при отметке готовности к доставке: restaurantId={}, orderId={}",
                    restaurantId,
                    orderId
            );

            throw new ResourceNotFoundException(
                    "Заказ с id=" + orderId + " не найден для restaurantId=" + restaurantId
            );
        }

        if (!orderStatusSnapshot.status().canBeMarkedReadyForDeliveryByRestaurant()) {
            log.warn(
                    "Нельзя отметить заказ готовым к доставке в текущем статусе: restaurantId={}, orderId={}, currentStatus={}",
                    restaurantId,
                    orderId,
                    orderStatusSnapshot.status().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Отметить готовым к доставке можно только заказ в статусе COOKING. Текущий статус="
                            + orderStatusSnapshot.status().getDbValue()
            );
        }

        updateOrderStatusToReadyForDelivery(restaurantId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_READY_FOR_DELIVERY,
                "Заказ отмечен рестораном как готовый к доставке"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Заказ отмечен готовым к доставке: restaurantId={}, orderId={}, newStatus={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }

    private void updateOrderStatusToReadyForDelivery(Long restaurantId, Long orderId) {
        String sql = """
                UPDATE food_app.customer_order
                set status = ?,
                    updated_at = CURRENT_TIMESTAMP
                where restaurant_id = ?
                and id = ?
                and status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                OrderStatus.READY_FOR_DELIVERY.getDbValue(),
                restaurantId,
                orderId,
                OrderStatus.COOKING.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось отметить заказ готовым к доставке: orderId=" + orderId
                            + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private void updateOrderStatusToCooking(Long restaurantId, Long orderId) {
        String sql = """
                UPDATE food_app.customer_order
                set status = ?,
                    updated_at = CURRENT_TIMESTAMP
                where id = ?
                and restaurant_id = ?
                and status = ?
                """;

        int updatedRows = jdbcTemplate.update(
                sql,
                OrderStatus.COOKING.getDbValue(),
                orderId,
                restaurantId,
                OrderStatus.ACCEPTED.getDbValue()
        );

        if (updatedRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось начать готовку заказа с id=" + orderId
                            + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private void updateOrderStatusToAccepted(Long restaurantId, Long orderId) {
        String sql = """
                UPDATE food_app.customer_order 
                set status = ?,
                    updated_at = current_timestamp
                where id = ?
                and restaurant_id = ?
                and status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                OrderStatus.ACCEPTED.getDbValue(),
                orderId,
                restaurantId,
                OrderStatus.CREATED.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось принять заказ с id=" + orderId
                            + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private OrderStatusSnapshot findOrderStatusForRestaurant(Long restaurantId, Long orderId) {

        String sql = """
                SELECT 
                    co.id,
                    co.status
                from food_app.customer_order co
                where co.id = ?
                and co.restaurant_id = ?
                """;

        List<OrderStatusSnapshot> statuses = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OrderStatusSnapshot(
                        rs.getLong("id"),
                        OrderStatus.fromDbValue(rs.getString("status"))
                ),
                orderId,
                restaurantId
        );

        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private boolean existsOrderForCustomer(Long customerId, Long orderId) {
        String sql = """
                SELECT EXISTS (
                SELECT 1 FROM food_app.customer_order co
                where co.id = ?
                and co.customer_id = ?
                )
                """;

        Boolean exists = jdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                orderId,
                customerId
        );

        return Boolean.TRUE.equals(exists);
    }

    private void updateOrderStatusToCanceled(Long customerId, Long orderId) {
        String sql = """
                UPDATE food_app.customer_order
                set status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                and customer_id = ?
                and status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                OrderStatus.CANCELED.getDbValue(),
                orderId,
                customerId,
                OrderStatus.CREATED.getDbValue());

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось отменить заказ с id=" + orderId
                            + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private OrderStatusSnapshot findOrderStatusForCustomer(Long customerId, Long orderId) {
        String sql = """
                SELECT
                    co.id,
                    co.status
                FROM food_app.customer_order co
                where co.id = ?
                and co.customer_id = ?
                """;

        List<OrderStatusSnapshot> statuses = jdbcTemplate.query(sql,
                (rs, rowNum) -> new OrderStatusSnapshot(
                        rs.getLong("id"),
                        OrderStatus.fromDbValue(rs.getString("status"))
                ),
                orderId,
                customerId
        );

        return  statuses.isEmpty() ? null : statuses.get(0);
    }

    private OrderResponse getOrderById(Long orderId) {
        String sql = """
                SELECT
                    co.id AS order_id,
                    co.cart_id,
                    co.customer_id,
                    co.restaurant_id,
                    co.status AS order_status,
                    co.total_amount,

                    coi.id AS order_item_id,
                    coi.menu_item_id,
                    coi.menu_item_name,
                    coi.quantity,
                    coi.price,
                    coi.line_total
                FROM food_app.customer_order co
                LEFT JOIN food_app.customer_order_item coi ON coi.order_id = co.id
                WHERE co.id = ?
                ORDER BY coi.id
                """;

        List<OrderRow> rows = jdbcTemplate.query(sql, orderRowMapper, orderId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
        }

        return buildOrderResponse(rows);
    }

    private OrderResponse buildOrderResponse(List<OrderRow> rows) {
        OrderRow firstRow = rows.get(0);

        List<OrderItemResponse> items = new ArrayList<>();

        for (OrderRow row : rows) {
            if (row.orderItemId() != null) {
                items.add(
                        new OrderItemResponse(
                            row.orderItemId(),
                            row.menuItemId(),
                            row.menuItemName(),
                            row.quantity(),
                            row.price(),
                            row.lineTotal()
                        )
                );
            }
        }
        return new OrderResponse(
                firstRow.orderId(),
                firstRow.cartId(),
                firstRow.customerId(),
                firstRow.restaurantId(),
                firstRow.orderStatus(),
                firstRow.totalAmount(),
                items
        );
    }

    private void checkoutCart(Long cartId) {
        String sql = """
                UPDATE food_app.cart
                SET status = 'CHECKED_OUT',
                    updated_at = CURRENT_TIMESTAMP
                where id = ?
                and status = 'ACTIVE'
                """;

        int updateRows = jdbcTemplate.update(sql, cartId);

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось оформить корзину с id=" + cartId
                            + ". Возможно, корзина уже была оформлена или изменила статус"
            );
        }
    }

    private void createOrderEvent(
            Long orderId,
            OrderEventType eventType,
            String description
    ) {
        String sql = """
                INSERT INTO food_app.customer_order_event (
                                                           order_id,
                                                           event_type,
                                                           description
                )
                values (?,?,?)
        """;

        int updateRows = jdbcTemplate.update(sql, orderId, eventType.getDbValue(), description);

        if (updateRows != 1) {
            throw new IllegalStateException(
                    "Не удалось записать событие заказа: orderId=" + orderId
                            + ", eventType=" + eventType.getDbValue()
            );
        }
    }

    private void createOrderItems(Long orderId, List<CartItemForOrder> cartItems) {
        String sql = """
                INSERT INTO food_app.customer_order_item (
                                                          order_id,
                                                          menu_item_id,
                                                          menu_item_name,
                                                          quantity,
                                                          price,
                                                          line_total
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        int[] updatedRows  = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {

                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        CartItemForOrder item = cartItems.get(i);

                        ps.setLong(1, orderId);
                        ps.setLong(2, item.menuItemId);
                        ps.setString(3, item.menuItemName);
                        ps.setInt(4, item.quantity);
                        ps.setBigDecimal(5, item.price);
                        ps.setBigDecimal(6, item.lineTotal);
                    }

                    @Override
                    public int getBatchSize() {
                        return cartItems.size();
                    }
                }
        );

        if (updatedRows.length != cartItems.size()) {
            throw new IllegalStateException(
                    "Не удалось создать все позиции заказа: orderId=" + orderId
            );
        }
    }

    private Long createOrder(Long cartId, Long customerId, Long restaurantId, BigDecimal totalAmount) {
        String sql = """
                INSERT INTO food_app.customer_order (
                                                     cart_id,
                                                     customer_id,
                                                     restaurant_id,
                                                     status,
                                                     total_amount
                )
                VALUES (?, ?, ?, ?, ?)
                RETURNING id;
                """;

        Long orderId = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                cartId,
                customerId,
                restaurantId,
                OrderStatus.CREATED.getDbValue(),
                totalAmount);

        if (orderId == null) {
            throw new IllegalStateException(
                    "База данных не вернула id созданного заказа для customerId="
                            + customerId + " и restaurantId=" + restaurantId
            );
        }

        return orderId;
    }

    private BigDecimal calculateTotalAmount(List<CartItemForOrder> cartItems) {
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartItemForOrder cartItem : cartItems) {
            totalAmount = totalAmount.add(cartItem.lineTotal());
        }

        return totalAmount;
    }

    private List<CartItemForOrder> findCartItemsForOrder(Long cartId) {

        String sql = """
                SELECT
                   ci.menu_item_id,
                   mi.name as menu_item_name,
                   ci.quantity,
                   ci.price
                from food_app.cart_item ci 
                join food_app.menu_item mi on ci.menu_item_id = mi.id
                where ci.cart_id = ?
                order by ci.created_at, ci.id
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    BigDecimal price = rs.getBigDecimal("price");
                    Integer quantity = rs.getObject("quantity", Integer.class);
                    BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(quantity));

                    return new CartItemForOrder(
                            rs.getLong("menu_item_id"),
                            rs.getString("menu_item_name"),
                            quantity,
                            price,
                            lineTotal
                    );
                },
                cartId
        );
    }

    private Long findActiveCartId(Long customerId, Long restaurantId) {

        String sql = """
                SELECT c.id
                from food_app.cart c
                where c.customer_id = ?
                and c.restaurant_id = ?
                and c.status = 'ACTIVE'
                """;

        List<Long> cartIds = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getLong("id"),
                customerId,
                restaurantId);

        return cartIds.isEmpty() ? null : cartIds.get(0);
    }



    private record CartItemForOrder(
            Long menuItemId,
            String menuItemName,
            Integer quantity,
            BigDecimal price,
            BigDecimal lineTotal
    ) {
    }

    private record OrderStatusSnapshot(
            Long orderId,
            OrderStatus status
    ) {
    }

}
