package com.dima.fooddelivery.client;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Контейнер PostgreSQL для контрактного теста.
 *
 * <p>Дублирует конфигурацию из тестов модуля service, и это осознанно: тестовые классы
 * не попадают в опубликованный артефакт, поэтому переиспользовать ту конфигурацию можно
 * было бы только через test-jar. Ради одного бина это лишняя связь между модулями —
 * client стал бы зависеть от внутреннего устройства тестов service.
 *
 * <p>{@code withUrlParam} обязателен по той же причине, что и там: без него потеряется
 * currentSchema и весь SQL без префикса схемы перестанет резолвиться.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ClientTestcontainersConfiguration {

    private static final String APP_SCHEMA = "food_app";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16")
                .withUrlParam("currentSchema", APP_SCHEMA + ",public");
    }
}
