package com.dima.fooddelivery.delivery.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.delivery.persistence.DeliveryRow;
import com.dima.fooddelivery.delivery.persistence.DeliveryRowMapper;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryService {

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryRowMapper deliveryRowMapper;

    @Transactional
    public DeliveryResponse createDeliveryForOrder(Long orderId) {
        log.info(
                "Начинаем создание доставки для заказа: orderId={}",
                orderId
        );

        OrderForDeliverySnapshot order = findOrderForDelivery(orderId);

        if (order == null) {
            log.warn(
                    "Заказ для создания доставки не найден: orderId={}",
                    orderId
            );

            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
        }

        if (!order.status().canCreateDelivery()) {
            log.warn(
                    "Нельзя создать доставку для заказа в текущем статусе: orderId={}, currentStatus={}",
                    orderId,
                    order.status().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Доставку можно создать только для заказа в статусе READY_FOR_DELIVERY. Текущий статус="
                            + order.status().getDbValue()
            );
        }

        Long deliveryId = createDelivery(orderId);

        DeliveryResponse response = getDeliveryById(deliveryId);

        log.info(
                "Доставка создана для заказа: deliveryId={}, orderId={}, status={}",
                response.id(),
                response.orderId(),
                response.status()
        );

        return response;
    }

    @Transactional
    public DeliveryResponse assignCourierToDelivery(Long courierId, Long deliveryId) {
        log.info(
                "Начинаем назначение курьера на доставку: deliveryId={}, courierId={}",
                deliveryId,
                courierId
        );

        validationCourierId(courierId);

        DeliveryStatusSnapshot deliveryStatusSnapshot = findDeliveryStatus(deliveryId);

        if (deliveryStatusSnapshot == null) {
            log.warn(
                    "Доставка для назначения курьера не найдена: deliveryId={}, courierId={}",
                    deliveryId,
                    courierId
            );

            throw new ResourceNotFoundException("Доставка с id=" + deliveryId + " не найдена");
        }

        if (!deliveryStatusSnapshot.status().canBeAssignedToCourier()) {
            log.warn(
                    "Нельзя назначить курьера на доставку в текущем статусе: deliveryId={}, courierId={}, currentStatus={}",
                    deliveryId,
                    courierId,
                    deliveryStatusSnapshot.status().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Курьера можно назначить только на доставку в статусе CREATED. Текущий статус="
                    + deliveryStatusSnapshot.status().getDbValue()
            );
        }

        updateDeliveryCourier(deliveryId, courierId);

        DeliveryResponse response = getDeliveryById(deliveryId);

        log.info(
                "Курьер назначен на доставку: deliveryId={}, courierId={}, newStatus={}",
                response.id(),
                response.courierId(),
                response.status()
        );

        return response;
    }

    @Transactional
    public DeliveryResponse pickUpDelivery(Long courierId, Long deliveryId) {
        log.info(
                "Начинаем забор заказа курьером: deliveryId={}, courierId={}",
                deliveryId,
                courierId
        );

        validationCourierId(courierId);

        DeliveryForPickupSnapshot  delivery = findDeliveryForPickup(deliveryId);

        if (delivery == null) {
            log.warn(
                    "Доставка для забора заказа не найдена: deliveryId={}, courierId={}",
                    delivery,
                    courierId
            );

            throw new ResourceNotFoundException("Доставка с id=" + deliveryId + " не найдена");
        }

        if (!courierId.equals(delivery.courierId())) {
            log.warn(
                    "Курьер не назначен на эту доставку: deliveryId={}, expectedCourierId={}, actualCourierId={}",
                    deliveryId,
                    delivery.courierId(),
                    courierId
            );

            throw new BusinessRuleViolationException("Эта доставка назначена другому курьеру");
        }

        if (!delivery.deliveryStatus().canBePickedUpByCourier()) {
            log.warn(
                    "Нельзя забрать доставку в текущем статусе: deliveryId={}, currentDeliveryStatus={}",
                    deliveryId,
                    delivery.deliveryStatus().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Забрать можно только доставку в статусе ASSIGNED. Текущий статус="
                            + delivery.deliveryStatus().getDbValue()
            );
        }

        if (!delivery.orderStatus().canBeMovedToInDeliveryByCourier()) {
            log.warn(
                    "Нельзя перевести заказ в доставку из текущего статуса: orderId={}, currentOrderStatus={}",
                    delivery.orderId(),
                    delivery.orderStatus().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Заказ можно передать курьеру только из статуса READY_FOR_DELIVERY. Текущий статус="
                            + delivery.orderStatus().getDbValue()
            );
        }

        updateDeliveryStatusToPickedUp(deliveryId, courierId);

        updateOrderStatusToInDelivery(delivery.orderId());

        createOrderEvent(
                delivery.orderId(),
                OrderEventType.ORDER_PICKED_UP_BY_COURIER,
                "Заказ забран курьером из ресторана"
        );

        DeliveryResponse response = getDeliveryById(deliveryId);

        log.info(
                "Забор заказа курьером успешно завершён: deliveryId={}, courierId={}, orderId={}, deliveryStatus={}",
                response.id(),
                response.courierId(),
                response.orderId(),
                response.status()
        );

        return response;
    }

    @Transactional
    public DeliveryResponse deliverDelivery(Long courierId, Long deliveryId) {
        log.info(
                "Начинаем завершение доставки: deliveryId={}, courierId={}",
                deliveryId,
                courierId
        );

        validationCourierId(courierId);

        DeliveryForCompletionSnapshot delivery = findDeliveryCompletion(deliveryId);

        if (delivery == null) {
            log.warn(
                    "Доставка для завершения не найдена: deliveryId={}, courierId={}",
                    deliveryId,
                    courierId
            );

            throw new ResourceNotFoundException("Доставка с id=" + deliveryId + " не найдена");
        }

        if (!courierId.equals(delivery.courierId())) {
            log.warn(
                    "Курьер не назначен на эту доставку: deliveryId={}, expectedCourierId={}, actualCourierId={}",
                    deliveryId,
                    delivery.courierId(),
                    courierId
            );

            throw new BusinessRuleViolationException("Эта доставка назначена другому курьеру");
        }

        if (!delivery.deliveryStatus().canBeDeliveredByCourier()) {
            log.warn(
                    "Нельзя завершить доставку в текущем статусе: deliveryId={}, currentDeliveryStatus={}",
                    deliveryId,
                    delivery.deliveryStatus().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Завершить можно только доставку в статусе PICKED_UP. Текущий статус="
                            + delivery.deliveryStatus().getDbValue()
            );
        }

        if (!delivery.orderStatus().canBeCompletedByCourier()) {
            log.warn(
                    "Нельзя завершить заказ из текущего статуса: orderId={}, currentOrderStatus={}",
                    delivery.orderId(),
                    delivery.orderStatus().getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Заказ можно завершить только из статуса IN_DELIVERY. Текущий статус="
                            + delivery.orderStatus().getDbValue()
            );
        }

        updateDeliveredStatusToDelivered(deliveryId, courierId);
        
        updateOrderStatusToDelivered(delivery.orderId());

        createOrderEvent(
                delivery.orderId(),
                OrderEventType.ORDER_DELIVERED,
                "Заказ доставлен клиенту"
        );

        DeliveryResponse response = getDeliveryById(deliveryId);

        log.info(
                "Доставка успешно завершена: deliveryId={}, courierId={}, orderId={}, deliveryStatus={}",
                response.id(),
                response.courierId(),
                response.orderId(),
                response.status()
        );

        return response;
    }

    private void updateOrderStatusToDelivered(Long orderId) {
        String sql = """
                UPDATE food_app.customer_order
                SET status = ?,
                    updated_at = current_timestamp
                where id = ?
                and status = ?
                """;

        int updatedRows = jdbcTemplate.update(
                sql,
                OrderStatus.DELIVERED.getDbValue(),
                orderId,
                OrderStatus.IN_DELIVERY.getDbValue()
        );

        if (updatedRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось перевести заказ в статус DELIVERED: orderId=" + orderId
                            + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private void updateDeliveredStatusToDelivered(Long deliveryId, Long courierId) {
        String sql = """
                UPDATE food_app.delivery 
                SET status = ?,
                    delivered_at = current_timestamp,
                    updated_at = current_timestamp
                WHERE id = ?
                and courier_id = ?
                and status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                DeliveryStatus.DELIVERED.getDbValue(),
                deliveryId,
                courierId,
                DeliveryStatus.PICKED_UP.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось завершить доставку: deliveryId=" + deliveryId
                    + ". Возможно, доставка уже изменила статус или назначена другому курьеру"
            );
        }
    }

    private DeliveryForCompletionSnapshot findDeliveryCompletion(Long deliveryId) {
        String sql = """
                SELECT 
                    d.id as delivery_id,
                    d.order_id,
                    d.courier_id,
                    d.status as delivery_status,
                    co.status as order_status
                FROM delivery AS d
                JOIN food_app.customer_order co on d.order_id = co.id
                WHERE d.id = ?
                """;

        List<DeliveryForCompletionSnapshot> deliveries = jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new DeliveryForCompletionSnapshot(
                        resultSet.getLong("delivery_id"),
                        resultSet.getLong("order_id"),
                        resultSet.getObject("courier_id", Long.class),
                        DeliveryStatus.fromDbValue(resultSet.getString("delivery_status")),
                        OrderStatus.fromDbValue(resultSet.getString("order_status"))
                ),
                deliveryId
        );

        return deliveries.isEmpty() ? null : deliveries.get(0);
    }

    private void createOrderEvent(Long orderId, OrderEventType orderEventType, String description) {
        String sql = """
                INSERT INTO food_app.customer_order_event (
                                                     order_id,
                                                     event_type,
                                                     description
                )
                VALUES (?, ?, ?)
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                orderId,
                orderEventType.getDbValue(),
                description
        );

        if (updateRows != 1) {
            throw new IllegalStateException(
                    "Не удалось записать событие заказа: orderId=" + orderId
                    + ", eventType=" + orderEventType.getDbValue()
            );
        }
    }

    private void updateOrderStatusToInDelivery(Long orderId) {
        String sql = """
                UPDATE food_app.customer_order
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                and status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                OrderStatus.IN_DELIVERY.getDbValue(),
                orderId,
                OrderStatus.READY_FOR_DELIVERY.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось перевести заказ в статус IN_DELIVERY: orderId=" + orderId
                   + ". Возможно, заказ уже изменил статус"
            );
        }
    }

    private void updateDeliveryStatusToPickedUp(Long deliveryId, Long courierId) {
        String sql = """
                UPDATE food_app.delivery
                SET status = ?,
                    picked_up_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                AND courier_id = ?
                AND status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                DeliveryStatus.PICKED_UP.getDbValue(),
                deliveryId,
                courierId,
                DeliveryStatus.ASSIGNED.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось отметить доставку как забранную: deliveryId=" + deliveryId
                            + ". Возможно, доставка уже изменила статус или назначена другому курьеру"
            );
        }
    }

    private DeliveryForPickupSnapshot findDeliveryForPickup(Long deliveryId) {
        String sql = """
            SELECT
                d.id AS delivery_id,
                d.order_id,
                d.courier_id,
                d.status AS delivery_status,
                co.status AS order_status
            FROM food_app.delivery d
            JOIN food_app.customer_order co ON co.id = d.order_id
            WHERE d.id = ?
            """;

        List<DeliveryForPickupSnapshot> deliveries = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DeliveryForPickupSnapshot(
                        rs.getLong("delivery_id"),
                        rs.getLong("order_id"),
                        rs.getObject("courier_id", Long.class),
                        DeliveryStatus.fromDbValue(rs.getString("delivery_status")),
                        OrderStatus.fromDbValue(rs.getString("order_status"))
                ),
                deliveryId
        );

        return deliveries.isEmpty() ? null : deliveries.get(0);
    }

    private void updateDeliveryCourier(Long deliveryId, Long courierId) {
        String sql = """
                UPDATE food_app.delivery
                SET courier_id = ?,
                    status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                AND status = ?
                """;

        int updateRows = jdbcTemplate.update(
                sql,
                courierId,
                DeliveryStatus.ASSIGNED.getDbValue(),
                deliveryId,
                DeliveryStatus.CREATED.getDbValue()
        );

        if (updateRows == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось назначить курьера на доставку с id=" + deliveryId
                            + ". Возможно, доставка уже изменила статус");
        }
    }

    private DeliveryStatusSnapshot findDeliveryStatus(Long deliveryId) {
        String sql = """
                SELECT 
                 d.id,
                 d.status,
                 d.courier_id
                FROM food_app.delivery d
                where d.id = ?
                """;

        List<DeliveryStatusSnapshot> deliveries = jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new DeliveryStatusSnapshot(
                        resultSet.getLong("id"),
                        DeliveryStatus.fromDbValue(resultSet.getString("status")),
                        resultSet.getObject("courier_id", Long.class)
                ),
                deliveryId
        );

        return deliveries.isEmpty() ? null : deliveries.get(0);
    }

    private void validationCourierId(Long courierId) {
        if (courierId == null || courierId <= 0) {
            throw new IllegalArgumentException("Идентификатор курьера должен быть положительным числом");
        }
    }

    private DeliveryResponse getDeliveryById(Long deliveryId) {
        String sql = """
                SELECT
                    d.id,
                    d.order_id,
                    d.courier_id,
                    d.status,
                    d.created_at,
                    d.updated_at,
                    d.picked_up_at,
                    d.delivered_at
                FROM food_app.delivery d
                WHERE d.id = ?
                """;

        List<DeliveryRow> rows = jdbcTemplate.query(sql, deliveryRowMapper, deliveryId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Доставка с id=" + deliveryId + " не найдена"
            );
        }

        return buildDeliveryResponse(rows.get(0));

    }

    private DeliveryResponse buildDeliveryResponse(DeliveryRow row) {
        return new DeliveryResponse(
                row.id(),
                row.orderId(),
                row.courierId(),
                row.status(),
                row.createdAt(),
                row.updatedAt(),
                row.pickedUpAt(),
                row.deliveredAt()
        );
    }

    private OrderForDeliverySnapshot findOrderForDelivery(Long orderId) {

        String sql = """
                SELECT
                    co.id,
                    co.status
                FROM food_app.customer_order co 
                WHERE co.id = ?
                """;

        List<OrderForDeliverySnapshot> orders = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OrderForDeliverySnapshot(
                        rs.getLong("id"),
                        OrderStatus.fromDbValue(rs.getString("status"))
                        ),
                        orderId
                );

        return orders.isEmpty() ? null : orders.get(0);

    }

    private Long createDelivery(Long orderId) {
        String sql = """
                INSERT INTO food_app.delivery (
                                               order_id,
                                               status
                )
                VALUES (?, ?)
                RETURNING id
                """;

        try {
            Long deliveryId = jdbcTemplate.queryForObject(
                    sql,
                    Long.class,
                    orderId,
                    DeliveryStatus.CREATED.getDbValue()
            );

            if (deliveryId == null) {
                throw new IllegalStateException(
                        "База данных не вернула id созданной доставки для orderId=" + orderId
                );
            }

            return deliveryId;
        } catch (DuplicateKeyException e) {
            log.warn(
                    "Доставка для заказа уже существует: orderId={}",
                    orderId
            );

            throw new BusinessRuleViolationException(
                    "Доставка для заказа с id=" + orderId + " уже существует"
            );
        }
    }



    private record OrderForDeliverySnapshot(
            Long orderId,
            OrderStatus status
    ) {
    }

    private record DeliveryStatusSnapshot(
            Long deliveryId,
            DeliveryStatus status,
            Long ccurierId
    ) {
    }

    private record DeliveryForPickupSnapshot(
            Long deliveryId,
            Long orderId,
            Long courierId,
            DeliveryStatus deliveryStatus,
            OrderStatus orderStatus
    ) {
    }

    private record DeliveryForCompletionSnapshot(
            Long deliveryId,
            Long orderId,
            Long courierId,
            DeliveryStatus deliveryStatus,
            OrderStatus orderStatus
    ) {
    }
}
