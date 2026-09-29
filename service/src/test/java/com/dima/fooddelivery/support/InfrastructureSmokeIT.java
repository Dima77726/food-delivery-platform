package com.dima.fooddelivery.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет саму инфраструктуру, а не бизнес-логику: контейнер поднялся, миграции накатились,
 * а search_path указывает на схему приложения.
 *
 * <p>Последнее — главное. Если {@code currentSchema} потеряется при передаче конфигурации из
 * контейнера в Spring, весь SQL без префикса схемы перестанет работать, и упадут все остальные
 * интеграционные тесты сразу. Пусть тогда падает этот, с понятным сообщением.
 */
class InfrastructureSmokeIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldResolveApplicationSchemaWithoutExplicitPrefix() {
        String schema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);

        assertEquals("food_app", schema);
    }

    @Test
    void shouldApplyLiquibaseMigrations() {
        Integer restaurants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restaurant",
                Integer.class
        );

        assertTrue(restaurants != null && restaurants > 0, "Миграции должны были засеять рестораны");
    }

    @Test
    void shouldReachDeliveryTableWithoutSchemaPrefix() {
        Integer deliveries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery",
                Integer.class
        );

        assertEquals(0, deliveries);
    }
}
