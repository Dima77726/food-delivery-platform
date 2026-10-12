package com.dima.fooddelivery.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Настоящие PostgreSQL и Redis для тестов каталога.
 *
 * <p>Redis — настоящий, а не {@code spring.cache.type=simple}: подмена прошла бы мимо всего,
 * что в кэше ломается на деле, — сериализации доменных типов в JSON и TTL.
 *
 * <p>Монолита в тестах нет. Права на рестораны, о которых каталог спрашивает его по gRPC,
 * отвечает {@link InMemoryRestaurantAccessClient}; сам gRPC-клиент проверяется отдельно,
 * против сервера в памяти процесса.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16");
    }

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer("redis:7-alpine");
    }

    /** {@code @Primary}: настоящий gRPC-клиент в контексте тоже есть, но его канал ленивый и молчит. */
    @Bean
    @Primary
    InMemoryRestaurantAccessClient inMemoryRestaurantAccessClient() {
        return new InMemoryRestaurantAccessClient();
    }

    @Bean
    TestJwt testJwt() {
        return new TestJwt();
    }

    /**
     * Декодер с открытым ключом {@link TestJwt} вместо JWKS сервиса пользователей — его в тестах
     * нет. {@code @Primary}: настоящий декодер в контексте тоже есть, но JWKS он скачивает лениво
     * и до первого токена никуда не ходит.
     */
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestJwt.decoder();
    }

    @Bean
    TestDataFactory testDataFactory(JdbcTemplate jdbcTemplate) {
        return new TestDataFactory(jdbcTemplate);
    }
}
