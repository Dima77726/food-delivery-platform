package com.dima.fooddelivery.user.persistence;

import com.dima.fooddelivery.user.domain.AppUser;
import com.dima.fooddelivery.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class AppUserRepository {

    /**
     * Пользователь с ролями одним запросом. LEFT JOIN, а не отдельный запрос за ролями:
     * пользователей читают на каждом запросе с токеном, и второй round-trip тут лишний.
     */
    private static final String SELECT_USER = """
            SELECT
                u.id, u.email, u.password_hash, u.full_name, u.phone,
                u.is_enabled, u.created_at,
                r.role
            FROM app_user u
            LEFT JOIN app_user_role r ON r.user_id = u.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<AppUser> findById(Long userId) {
        return assembleSingle(jdbc.query(
                SELECT_USER + " WHERE u.id = :userId",
                new MapSqlParameterSource("userId", userId),
                UserRowMappers.USER_ROW
        ));
    }

    /** Поиск по e-mail без учёта регистра — так же, как работает уникальный индекс. */
    public Optional<AppUser> findByEmail(String email) {
        return assembleSingle(jdbc.query(
                SELECT_USER + " WHERE LOWER(u.email) = LOWER(:email)",
                new MapSqlParameterSource("email", email),
                UserRowMappers.USER_ROW
        ));
    }

    public boolean existsByEmail(String email) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app_user WHERE LOWER(email) = LOWER(:email))",
                new MapSqlParameterSource("email", email),
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    public List<AppUser> findAll() {
        return assembleAll(jdbc.query(SELECT_USER + " ORDER BY u.id", UserRowMappers.USER_ROW));
    }

    public Long insert(String email, String passwordHash, String fullName, String phone) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("passwordHash", passwordHash)
                .addValue("fullName", fullName)
                .addValue("phone", phone);

        return jdbc.queryForObject(
                """
                        INSERT INTO app_user (email, password_hash, full_name, phone)
                        VALUES (:email, :passwordHash, :fullName, :phone)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int addRole(Long userId, UserRole role) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("role", role.name());

        return jdbc.update(
                """
                        INSERT INTO app_user_role (user_id, role)
                        VALUES (:userId, :role)
                        ON CONFLICT (user_id, role) DO NOTHING
                        """,
                params
        );
    }

    public int setEnabled(Long userId, boolean enabled) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("enabled", enabled);

        return jdbc.update(
                """
                        UPDATE app_user
                        SET is_enabled = :enabled,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :userId
                        """,
                params
        );
    }

    public boolean managesRestaurant(Long userId, Long restaurantId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("restaurantId", restaurantId);

        Boolean exists = jdbc.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM restaurant_manager
                            WHERE user_id = :userId AND restaurant_id = :restaurantId
                        )
                        """,
                params,
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    public List<Long> findManagedRestaurantIds(Long userId) {
        return jdbc.query(
                "SELECT restaurant_id FROM restaurant_manager WHERE user_id = :userId ORDER BY restaurant_id",
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getLong("restaurant_id")
        );
    }

    public int addRestaurantManager(Long userId, Long restaurantId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("restaurantId", restaurantId);

        return jdbc.update(
                """
                        INSERT INTO restaurant_manager (user_id, restaurant_id)
                        VALUES (:userId, :restaurantId)
                        ON CONFLICT (user_id, restaurant_id) DO NOTHING
                        """,
                params
        );
    }

    private Optional<AppUser> assembleSingle(List<UserRow> rows) {
        return assembleAll(rows).stream().findFirst();
    }

    private List<AppUser> assembleAll(List<UserRow> rows) {
        Map<Long, UserAccumulator> byId = new LinkedHashMap<>();

        for (UserRow row : rows) {
            UserAccumulator accumulator = byId.computeIfAbsent(row.id(), id -> new UserAccumulator(row));

            if (row.role() != null) {
                accumulator.roles.add(UserRole.fromDbValue(row.role()));
            }
        }

        return byId.values().stream().map(UserAccumulator::toUser).toList();
    }

    record UserRow(
            Long id,
            String email,
            String passwordHash,
            String fullName,
            String phone,
            boolean enabled,
            OffsetDateTime createdAt,
            String role
    ) {
    }

    private static final class UserAccumulator {

        private final UserRow first;
        private final Set<UserRole> roles = EnumSet.noneOf(UserRole.class);

        private UserAccumulator(UserRow first) {
            this.first = first;
        }

        private AppUser toUser() {
            return new AppUser(
                    first.id(),
                    first.email(),
                    first.passwordHash(),
                    first.fullName(),
                    first.phone(),
                    first.enabled(),
                    // Set.copyOf, а не EnumSet.copyOf: последний бросает исключение на пустом
                    // множестве, а пользователь без ролей — законное состояние.
                    Set.copyOf(roles),
                    first.createdAt()
            );
        }
    }
}
