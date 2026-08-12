package com.dima.fooddelivery.user.persistence;

import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;

final class UserRowMappers {

    static final RowMapper<AppUserRepository.UserRow> USER_ROW = (rs, rowNum) -> new AppUserRepository.UserRow(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getBoolean("is_enabled"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("role")
    );

    private UserRowMappers() {
    }
}
