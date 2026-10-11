package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.security.RestaurantAccessClient;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Права на рестораны для интеграционных тестов монолита — без identity-service.
 *
 * <p>Тот же приём, что у {@link LegacyTablesCatalogClient}: ответ берётся из старой таблицы
 * {@code restaurant_manager} в базе монолита, которую сам монолит больше не читает. Тест,
 * которому нужен владелец ресторана, вставляет строку SQL — так же, как вставлял до переезда
 * пользователей, — и gRPC-вызов в сервис пользователей ему для этого не нужен.
 *
 * <p>Сам gRPC-клиент проверяется отдельно, против сервера в памяти процесса.
 */
public class LegacyTablesRestaurantAccessClient implements RestaurantAccessClient {

    private final JdbcTemplate jdbcTemplate;

    public LegacyTablesRestaurantAccessClient(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean managesRestaurant(long userId, long restaurantId) {
        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restaurant_manager WHERE user_id = ? AND restaurant_id = ?",
                Integer.class,
                userId,
                restaurantId
        );

        return links != null && links > 0;
    }

    @Override
    public void assignManager(long userId, long restaurantId) {
        jdbcTemplate.update(
                "INSERT INTO restaurant_manager (user_id, restaurant_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId,
                restaurantId
        );
    }
}
