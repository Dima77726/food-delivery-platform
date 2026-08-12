package com.dima.fooddelivery.support;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.kafka.KafkaContainer;
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

    /**
     * Настоящий Redis, а не подмена кэша на in-memory.
     *
     * <p>Замена {@code spring.cache.type=simple} в тестах прошла бы мимо всего, что в кэше
     * может сломаться: сериализации доменных типов в JSON, TTL, поведения при недоступном
     * сервере. Проверялись бы только аннотации, а они и так очевидны.
     */
    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer("redis:7-alpine");
    }

    /**
     * Настоящий брокер, а не заглушка.
     *
     * <p>Здесь это принципиальнее, чем в случае с Redis: почти всё, что ломается в работе
     * с Kafka, — это поведение брокера, а не код. Порядок внутри партиции, ребалансировка,
     * фиксация офсетов, повторная доставка — ничего из этого мок не воспроизведёт.
     *
     * <p>Образ тот же, что в compose, и версия та же. Это не педантизм: у брокеров разных
     * сборок расходятся значения по умолчанию, и расхождение проявляется ровно там, где
     * его никто не ждёт — тест зелёный, а локальный стек ведёт себя иначе. Проверять
     * имеет смысл то, что запускается.
     *
     * <p>{@code KafkaContainer} из Testcontainers поднимает apache/kafka в режиме KRaft,
     * без ZooKeeper — как и compose.
     */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.9.0");
    }

    @Bean
    TestDataFactory testDataFactory(JdbcTemplate jdbcTemplate) {
        return new TestDataFactory(jdbcTemplate);
    }
}
