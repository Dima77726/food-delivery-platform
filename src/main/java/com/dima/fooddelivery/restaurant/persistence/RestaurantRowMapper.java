package com.dima.fooddelivery.restaurant.persistence;

import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class RestaurantRowMapper implements RowMapper<RestaurantResponse> {

    @Override
    public RestaurantResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RestaurantResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("city"),
                rs.getBoolean("is_active")
        );
    }
}
