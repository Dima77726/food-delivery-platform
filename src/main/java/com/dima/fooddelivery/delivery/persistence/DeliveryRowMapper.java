package com.dima.fooddelivery.delivery.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

@Component
public class DeliveryRowMapper implements RowMapper<DeliveryRow> {
    @Override
    public DeliveryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getObject("courier_id", Long.class),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("picked_up_at", OffsetDateTime.class),
                rs.getObject("delivered_at", OffsetDateTime.class)
        );
    }
}
