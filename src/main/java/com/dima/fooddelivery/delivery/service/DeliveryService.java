package com.dima.fooddelivery.delivery.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.delivery.persistence.DeliveryRow;
import com.dima.fooddelivery.delivery.persistence.DeliveryRowMapper;
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

            throw new IllegalStateException(
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

            throw new IllegalStateException(
                    "Доставка для заказа с id=" + orderId + " уже существует"
            );
        }
    }

    private record OrderForDeliverySnapshot(
            Long orderId,
            OrderStatus status
    ) {
    }
}
