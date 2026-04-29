package com.dima.fooddelivery.order.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class OrderRowMapper implements RowMapper<OrderRow> {
    @Override
    public OrderRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new  OrderRow(
                rs.getLong("order_id"),
                rs.getLong("cart_id"),
                rs.getLong("customer_id"),
                rs.getLong("restaurant_id"),
                rs.getString("order_status"),
                rs.getBigDecimal("total_amount"),

                rs.getObject("order_item_id", Long.class),
                rs.getObject("menu_item_id", Long.class),
                rs.getString("menu_item_name"),
                rs.getObject("quantity", Integer.class),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("line_total")

        );
    }
}
