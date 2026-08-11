package com.dima.fooddelivery.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiquibaseMigrationTest {

    private static final String LATEST_CHANGESET_ID = "017-fix-order-item-money-constraints";

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyLatestMigrationAndUseTwoDecimalPlacesForOrderItemPrice() {
        Integer appliedChangesets = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM liquibase_meta.databasechangelog
                        WHERE id = ?
                        """,
                Integer.class,
                LATEST_CHANGESET_ID
        );

        MoneyColumnDefinition priceDefinition = jdbcTemplate.queryForObject(
                """
                        SELECT numeric_precision, numeric_scale
                        FROM information_schema.columns
                        WHERE table_schema = 'food_app'
                          AND table_name = 'customer_order_item'
                          AND column_name = 'price'
                        """,
                (resultSet, rowNum) -> new MoneyColumnDefinition(
                        resultSet.getInt("numeric_precision"),
                        resultSet.getInt("numeric_scale")
                )
        );

        assertAll(
                () -> assertEquals(1, appliedChangesets),
                () -> assertEquals(10, priceDefinition.precision()),
                () -> assertEquals(2, priceDefinition.scale())
        );
    }

    @Test
    void shouldRejectNegativeOrderItemLineTotal() {
        Long restaurantId = jdbcTemplate.queryForObject(
                "SELECT id FROM food_app.restaurant ORDER BY id LIMIT 1",
                Long.class
        );

        Long menuItemId = jdbcTemplate.queryForObject(
                """
                        SELECT mi.id
                        FROM food_app.menu_item mi
                        JOIN food_app.menu_category mc ON mc.id = mi.category_id
                        WHERE mc.restaurant_id = ?
                        ORDER BY mi.id
                        LIMIT 1
                        """,
                Long.class,
                restaurantId
        );

        Long customerId = 100_001L;
        Long cartId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO food_app.cart (customer_id, restaurant_id, status)
                        VALUES (?, ?, 'CHECKED_OUT')
                        RETURNING id
                        """,
                Long.class,
                customerId,
                restaurantId
        );

        Long orderId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO food_app.customer_order (
                            cart_id,
                            customer_id,
                            restaurant_id,
                            status,
                            total_amount
                        )
                        VALUES (?, ?, ?, 'CREATED', ?)
                        RETURNING id
                        """,
                Long.class,
                cartId,
                customerId,
                restaurantId,
                new BigDecimal("99.99")
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                                INSERT INTO food_app.customer_order_item (
                                    order_id,
                                    menu_item_id,
                                    menu_item_name,
                                    quantity,
                                    price,
                                    line_total
                                )
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                        orderId,
                        menuItemId,
                        "Invalid test item",
                        1,
                        new BigDecimal("99.99"),
                        new BigDecimal("-99.99")
                )
        );

        Integer savedItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM food_app.customer_order_item WHERE order_id = ?",
                Integer.class,
                orderId
        );

        assertEquals(0, savedItems);
    }

    private record MoneyColumnDefinition(int precision, int scale) {
    }
}
