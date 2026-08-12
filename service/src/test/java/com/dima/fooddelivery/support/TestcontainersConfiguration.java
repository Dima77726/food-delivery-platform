package com.dima.fooddelivery.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Контейнер PostgreSQL, общий для всей тестовой сборки.
 *
 * <p>Контейнер объявлен обычным бином, а не через {@code @Container} из JUnit-расширения.
 * Разница принципиальная: JUnit поднимал бы отдельный Postgres на каждый тест-класс, а бин живёт
 * внутри контекста Spring, который кэшируется между классами. Пока конфигурация контекста
 * совпадает, все тесты работают с одной базой и один раз прогоняют миграции.
 *
 * <p>{@link ServiceConnection} сам проставляет url, username и password из контейнера.
 * Ключевой момент — {@code withUrlParam}: без него JDBC-URL контейнера пришёл бы без
 * {@code currentSchema}, тесты потеряли бы search_path и весь SQL без префикса схемы перестал бы
 * резолвиться.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String APP_SCHEMA = "food_app";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16")
                .withUrlParam("currentSchema", APP_SCHEMA + ",public");
    }

    @Bean
    TestDataFactory testDataFactory(JdbcTemplate jdbcTemplate) {
        return new TestDataFactory(jdbcTemplate);
    }
}
