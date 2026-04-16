package com.dima.fooddelivery.menu.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class MenuRowMapper implements RowMapper<MenuRow> {

    @Override
    public MenuRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MenuRow(
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getInt("category_sort_order"),

                rs.getObject("item_id", Long.class),
                rs.getString("item_name"),
                rs.getString("item_description"),
                rs.getBigDecimal("item_price"),
                rs.getObject("item_available", Boolean.class),
                rs.getObject("item_sort_order", Integer.class)
        );
    }
}
