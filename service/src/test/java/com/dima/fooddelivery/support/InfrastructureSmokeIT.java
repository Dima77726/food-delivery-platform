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
        Integer initChecks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_init_check",
                Integer.class
        );

        assertTrue(initChecks != null && initChecks > 0, "Первая миграция должна была записать контрольную строку");
    }

    /**
     * Таблицы, уехавшие в другие сервисы, удалены последней миграцией. Если какая-то осталась,
     * значит, у неё нашлась живая ссылка, и миграция 029 тихо не сработала бы — а этого быть
     * не должно.
     */
    @Test
    void shouldNotKeepTablesMovedToOtherServices() {
        Integer leftovers = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = current_schema()
                          AND table_name IN ('restaurant', 'menu_category', 'menu_item',
                                             'app_user', 'app_user_role', 'restaurant_manager', 'notification')
                        """,
                Integer.class
        );

        assertTrue(leftovers != null && leftovers == 0, "старые таблицы должны быть удалены, осталось: " + leftovers);
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
