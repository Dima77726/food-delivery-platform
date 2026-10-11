package com.dima.fooddelivery.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Собирает состояние базы каталога для интеграционных тестов.
 *
 * <p>Данные вставляются напрямую через SQL, а не через сервисы: тест поиска должен падать
 * из-за поиска, а не из-за того, что сломалось создание меню.
 */
public class TestDataFactory {

    /**
     * Уникальные суффиксы для имён. У меню уникальные индексы по имени, а откат транзакции
     * не сбрасывает последовательности — поэтому счётчик, а не константа.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    private final JdbcTemplate jdbcTemplate;

    public TestDataFactory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long uniqueSuffix() {
        return SEQUENCE.incrementAndGet();
    }

    public Long insertRestaurant() {
        return insertRestaurant(true);
    }

    public Long insertRestaurant(boolean active) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO restaurant (name, description, city, is_active)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "Test Restaurant " + uniqueSuffix(),
                "Ресторан для интеграционных тестов",
                "Moscow",
                active
        );
    }

    public Long insertMenuCategory(Long restaurantId) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO menu_category (restaurant_id, name, sort_order)
                        VALUES (?, ?, 1)
                        RETURNING id
                        """,
                Long.class,
                restaurantId,
                "Test Category " + uniqueSuffix()
        );
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price) {
        return insertMenuItem(restaurantId, price, true);
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price, boolean available) {
        Long categoryId = insertMenuCategory(restaurantId);

        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO menu_item (category_id, name, description, price, is_available, sort_order)
                        VALUES (?, ?, ?, ?, ?, 1)
                        RETURNING id
                        """,
                Long.class,
                categoryId,
                "Test Item " + uniqueSuffix(),
                "Блюдо для интеграционных тестов",
                price,
                available
        );
    }
}
