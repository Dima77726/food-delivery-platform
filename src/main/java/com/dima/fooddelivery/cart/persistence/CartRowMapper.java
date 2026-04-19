package com.dima.fooddelivery.cart.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class CartRowMapper implements RowMapper<CartRow> {

    @Override
    public CartRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CartRow(
                rs.getLong("cart_id"),
                rs.getLong("customer_id"),
                rs.getLong("restaurant_id"),
                rs.getString("cart_status"),

                rs.getObject("cart_item_id", Long.class),
                rs.getObject("menu_item_id", Long.class),
                rs.getString("menu_item_name"),
                rs.getObject("quantity", Integer.class),
                rs.getBigDecimal("price")

        );
    }
}
