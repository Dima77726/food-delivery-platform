package com.dima.fooddelivery.support;

import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
     * Настоящий брокер, а не заглушка.
     *
     * <p>Здесь это принципиально: почти всё, что ломается в работе
     * с Kafka, — это поведение брокера, а не код. Порядок внутри партиции, ребалансировка,
     * фиксация офсетов, повторная доставка — ничего из этого мок не воспроизведёт.
     *
     * <p>Образ намеренно не тот, что в compose, — и на это есть причина, а не недосмотр.
     * Напрашивающийся {@code KafkaContainer("apache/kafka:3.9.0")} с брокером из compose
     * не стартует: Testcontainers задаёт контейнеру {@code KAFKA_LISTENERS} с адресом
     * {@code 0.0.0.0}, а {@code KAFKA_ADVERTISED_LISTENERS} экспортирует позже стартовым
     * скриптом — реальный порт известен только после запуска. Обёртка образа apache/kafka
     * успевает раньше: она вызывает {@code StorageTool}, тот не находит advertised-адресов,
     * берёт их из {@code listeners} и падает на «cannot use the nonroutable meta-address».
     *
     * <p>У образа Confluent другой entrypoint, этой гонки в нём нет, поэтому здесь пара
     * {@code ConfluentKafkaContainer} + cp-kafka. Оба брокера — та же Kafka в режиме KRaft,
     * различаются только обвязкой запуска, а она в тестах и не проверяется.
     */
    @Bean
    @ServiceConnection
    ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");
    }

    /**
     * Сервис заказов и права на рестораны в памяти вместо gRPC-клиентов к order-service
     * и identity-service. {@code @Primary}: настоящие клиенты в контексте тоже есть, но их
     * каналы ленивые и ни к кому не подключаются, пока их не вызвали.
     */
    @Bean
    @Primary
    TestOrders testOrders() {
        return new TestOrders();
    }

    @Bean
    @Primary
    TestRestaurantAccess testRestaurantAccess() {
        return new TestRestaurantAccess();
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
    TestDataFactory testDataFactory(TestOrders orders, TestRestaurantAccess restaurantAccess) {
        return new TestDataFactory(orders, restaurantAccess);
    }
}
