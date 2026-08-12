package com.dima.fooddelivery.restaurant.persistence;

import com.dima.fooddelivery.restaurant.domain.Restaurant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RestaurantRepository {

    private static final RowMapper<Restaurant> RESTAURANT = (rs, rowNum) -> new Restaurant(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("city"),
            rs.getBoolean("is_active")
    );

    private static final String SELECT_RESTAURANT = """
            SELECT id, name, description, city, is_active
            FROM restaurant
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<Restaurant> findAll() {
        return jdbc.query(SELECT_RESTAURANT + " ORDER BY id", RESTAURANT);
    }

    public Optional<Restaurant> findById(Long restaurantId) {
        return jdbc.query(
                SELECT_RESTAURANT + " WHERE id = :restaurantId",
                new MapSqlParameterSource("restaurantId", restaurantId),
                RESTAURANT
        ).stream().findFirst();
    }

    public boolean existsById(Long restaurantId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM restaurant WHERE id = :restaurantId)",
                new MapSqlParameterSource("restaurantId", restaurantId),
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    public Long insert(String name, String description, String city) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("description", description)
                .addValue("city", city);

        return jdbc.queryForObject(
                """
                        INSERT INTO restaurant (name, description, city, is_active)
                        VALUES (:name, :description, :city, TRUE)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    /**
     * Частичное обновление: NULL в параметре означает «не менять».
     *
     * <p>COALESCE вместо сборки SQL из непустых полей — запрос остаётся одной константой,
     * которую видно целиком. Ценой того, что стереть описание в NULL этим методом нельзя;
     * для «очистить» клиент присылает пустую строку.
     */
    public int update(Long restaurantId, String name, String description, String city) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("city", city);

        return jdbc.update(
                """
                        UPDATE restaurant
                        SET name = COALESCE(:name, name),
                            description = COALESCE(:description, description),
                            city = COALESCE(:city, city),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :restaurantId
                        """,
                params
        );
    }

    public int setActive(Long restaurantId, boolean active) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("active", active);

        return jdbc.update(
                """
                        UPDATE restaurant
                        SET is_active = :active,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :restaurantId
                        """,
                params
        );
    }
}
