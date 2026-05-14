package com.dima.fooddelivery.order.service;

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
        log.info("createOrderFromActiveCart customerId = {}, restaurantId = {}", customerId, restaurantId);

        Long cartId = findActiveCartId(customerId, restaurantId);

        if (cartId == null) {
            log.warn(
                    "Active cart not found while creating order: customerId={}, restaurantId={}",
                    customerId,
                    restaurantId
            );

            throw new ResourceNotFoundException("Active cart not found for customerId=" + customerId + " and restaurantId=" + restaurantId);
        }

        List<CartItemForOrder> cartItems = findCartItemsForOrder(cartId);

        if (cartItems.isEmpty()) {
            log.warn("Cannot create order from empty cart: cartId={}", cartId);

            throw new IllegalStateException("Cannot create order from empty cart: cartId=" + cartId);
        }

        BigDecimal totalAmount = calculateTotalAmount(cartItems);

        Long orderId = createOrder(cartId, customerId, restaurantId, totalAmount);

        createOrderItems(orderId, cartItems);

        checkoutCart(cartId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_CREATED,
                "Order created from active cart"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Order created from cart successfully: orderId={}, cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.cartId(),
                response.items().size(),
                response.totalAmount()
        );

        return response;

    }

    public OrderResponse getOrderByIdForCustomer(Long customerId, Long orderId) {
        log.info("Fetching order for customer: customerId = {}, orderId = {}",
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
            log.warn("Order not found for customer: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException( "Order with id=" + orderId + " not found for customerId=" + customerId);
        }

        OrderResponse response = buildOrderResponse(rows);

        log.info("Order loaded for customer: customerId={}, orderId={}, status={}, totalAmount={}, itemsCount={}",
                response.customerId(),
                response.id(),
                response.status(),
                response.totalAmount(),
                response.items().size()
                );

        return response;
    }

    public List<OrderSummaryResponse> getOrdersByCustomer(Long customerId) {
        log.info("Fetching orders for customer: customerId = {}", customerId);

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
                "Orders loaded for customer: customerId={}, ordersCount={}",
                customerId,
                orders.size()
        );

        return orders;
    }

    @Transactional
    public OrderResponse cancelOrder(Long customerId, Long orderId) {
        log.info(
                "Canceling order: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForCustomer(customerId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Order not found while canceling: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException("Order with id=" + orderId + " not found for customerId=" + customerId);
        }

        if (!orderStatusSnapshot.status().canBeCanceledByCustomer()) {
            log.warn(
                    "Order cannot be canceled: customerId={}, orderId={}, currentStatus={}",
                    customerId,
                    orderId,
                    orderStatusSnapshot.status()
            );

            throw new IllegalStateException("Only CREATED orders can be canceled. Current status=" + orderStatusSnapshot.status());
        }

        updateOrderStatusToCanceled(customerId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_CANCELED,
                "Order canceled by customer"
        );

        OrderResponse response = getOrderByIdForCustomer(customerId, orderId);

        log.info(
                "Order canceled: customerId={}, orderId={}, newStatus={}",
                customerId,
                orderId,
                response.status()
        );

        return response;
    }

    public List<OrderEventResponse> getOrderEventsForCustomer(Long customerId, Long orderId) {
        log.info(
                "Fetching order events for customer: customerId={}, orderId={}",
                customerId,
                orderId
        );

        boolean orderExist = existsOrderForCustomer(customerId, orderId);

        if (!orderExist) {
            log.warn(
                    "Order not found while fetching events: customerId={}, orderId={}",
                    customerId,
                    orderId
            );

            throw new ResourceNotFoundException("Order with id=" + orderId + " not found for customerId=" + customerId );
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
                "Order events loaded: customerId={}, orderId={}, eventsCount={}",
                customerId,
                orderId,
                events.size()
        );

        return events;
    }

    public OrderResponse acceptOrder(Long restaurantId, Long orderId) {
        log.info(
                "Accepting order: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForRestaurant(restaurantId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Order not found while accepting: restaurantId={}, orderId={}",
                    restaurantId,
                    orderId
            );

            throw new ResourceNotFoundException("Order with id=" + orderId + " not found for restaurantId=" + restaurantId);
        }

        if (!orderStatusSnapshot.status().canBeAcceptedByRestaurant()) {
            log.warn(
                    "Order cannot be accepted: restaurantId={}, orderId={}, currentStatus={}",
                    restaurantId,
                    orderId,
                    orderStatusSnapshot.status()
            );

            throw new IllegalStateException("Only CREATED orders can be accepted by restaurant. Current status=" +  orderStatusSnapshot.status().getDbValue());
        }

        updateOrderStatusToAccepted(restaurantId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_ACCEPTED,
                "Order accepted by restaurant"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Order accepted: restaurantId={}, orderId={}, newStatus={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }

    @Transactional
    public OrderResponse startCookingOrder(Long restaurantId, Long orderId) {
        log.info(
                "Starting cooking order: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderStatusSnapshot orderStatusSnapshot = findOrderStatusForRestaurant(restaurantId, orderId);

        if (orderStatusSnapshot == null) {
            log.warn(
                    "Order not found while starting cooking: restaurantId={}, orderId={}",
                    restaurantId,
                    orderId
            );

            throw new ResourceNotFoundException("Order with id=" + orderId + " not found for restaurantId=" + restaurantId);
        }

        if (!orderStatusSnapshot.status().canStartCookingByRestaurant()) {
            log.warn(
                    "Order cannot start cooking: restaurantId={}, orderId={}, currentStatus={}",
                    restaurantId,
                    orderId,
                    orderStatusSnapshot.status()
            );

            throw new IllegalStateException("Only ACCEPTED orders can start cooking. Current status=" + orderStatusSnapshot.status().getDbValue());
        }

        updateOrderStatusToCooking(restaurantId, orderId);

        createOrderEvent(
                orderId,
                OrderEventType.ORDER_COOKING_STARTED,
                "Order cooking started by restaurant"
        );

        OrderResponse response = getOrderById(orderId);

        log.info(
                "Order cooking started: restaurantId={}, orderId={}, newStatus={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
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
            throw new IllegalStateException("Failed to start cooking order with id=" + orderId + " for restaurantId=" + restaurantId);
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
            throw new IllegalStateException(
                    "Failed to accept order with id=" + orderId + " for restaurantId=" + restaurantId
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
            throw new IllegalStateException("Failed to cancel order with id=" + orderId + " for customerId=" + customerId);
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
            throw new ResourceNotFoundException("Order not found for orderId=" + orderId);
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
            throw new IllegalStateException("Failed to checkout active cart with id=" + cartId);
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
            throw new IllegalStateException("Failed to create order event for orderId=" + orderId + ", eventType=" + eventType.getDbValue());
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
            throw new IllegalStateException("Failed to create all order items for orderId=" + orderId);
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
            throw new ResourceNotFoundException( "Failed to create order for customerId=" + customerId + " and restaurantId=" + restaurantId);
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
