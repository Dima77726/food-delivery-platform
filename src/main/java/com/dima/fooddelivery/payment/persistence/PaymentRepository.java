package com.dima.fooddelivery.payment.persistence;

import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private static final RowMapper<Payment> PAYMENT = (rs, rowNum) -> new Payment(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("customer_id"),
            rs.getBigDecimal("amount"),
            PaymentStatus.fromDbValue(rs.getString("status")),
            PaymentMethod.fromDbValue(rs.getString("method")),
            rs.getString("idempotency_key"),
            rs.getString("failure_reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    private static final String SELECT_PAYMENT = """
            SELECT p.id, p.order_id, p.customer_id, p.amount, p.status, p.method,
                   p.idempotency_key, p.failure_reason, p.created_at, p.updated_at
            FROM payment p
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<Payment> findById(Long paymentId) {
        return jdbc.query(
                SELECT_PAYMENT + " WHERE p.id = :paymentId",
                new MapSqlParameterSource("paymentId", paymentId),
                PAYMENT
        ).stream().findFirst();
    }

    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(
                SELECT_PAYMENT + " WHERE p.idempotency_key = :idempotencyKey",
                new MapSqlParameterSource("idempotencyKey", idempotencyKey),
                PAYMENT
        ).stream().findFirst();
    }

    public Optional<Payment> findSucceededByOrderId(Long orderId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("status", PaymentStatus.SUCCEEDED.getDbValue());

        return jdbc.query(
                SELECT_PAYMENT + " WHERE p.order_id = :orderId AND p.status = :status",
                params,
                PAYMENT
        ).stream().findFirst();
    }

    public List<Payment> findByOrderId(Long orderId) {
        return jdbc.query(
                SELECT_PAYMENT + " WHERE p.order_id = :orderId ORDER BY p.created_at, p.id",
                new MapSqlParameterSource("orderId", orderId),
                PAYMENT
        );
    }

    public List<Payment> findByCustomerId(Long customerId) {
        return jdbc.query(
                SELECT_PAYMENT + " WHERE p.customer_id = :customerId ORDER BY p.created_at DESC, p.id DESC",
                new MapSqlParameterSource("customerId", customerId),
                PAYMENT
        );
    }

    public Long insert(
            Long orderId,
            Long customerId,
            BigDecimal amount,
            PaymentStatus status,
            PaymentMethod method,
            String idempotencyKey
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("customerId", customerId)
                .addValue("amount", amount)
                .addValue("status", status.getDbValue())
                .addValue("method", method.getDbValue())
                .addValue("idempotencyKey", idempotencyKey);

        return jdbc.queryForObject(
                """
                        INSERT INTO payment (order_id, customer_id, amount, status, method, idempotency_key)
                        VALUES (:orderId, :customerId, :amount, :status, :method, :idempotencyKey)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int compareAndSetStatus(Long paymentId, PaymentStatus expected, PaymentStatus next, String failureReason) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("paymentId", paymentId)
                .addValue("expected", expected.getDbValue())
                .addValue("next", next.getDbValue())
                .addValue("failureReason", failureReason);

        return jdbc.update(
                """
                        UPDATE payment
                        SET status = :next,
                            failure_reason = :failureReason,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :paymentId
                          AND status = :expected
                        """,
                params
        );
    }
}
