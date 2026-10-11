package com.dima.fooddelivery.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.neo4j.Neo4jContainer;

/**
 * Контейнеры дополнительных хранилищ монолита. Elasticsearch уехал с поиском в catalog-service,
 * Cassandra — с треком курьера в tracking-service.
 *
 * <p>Отдельная конфигурация, а не дополнение к {@link TestcontainersConfiguration}. Разница
 * принципиальная и стоит нескольких минут прогона: контекст Spring кэшируется по набору
 * своих настроек, и добавь мы эти контейнеры в общую конфигурацию — их поднимал бы
 * каждый интеграционный тест проекта, включая те, что проверяют SQL и не знают ни о каком
 * MongoDB. Так же поднимаются они ровно один раз и только для наследников
 * {@link AbstractStoresIntegrationTest}.
 *
 * <p><b>Два способа передать адрес приложению.</b> MongoDB подключается
 * через {@link ServiceConnection}: его настройки разбирает автоконфигурация Spring Boot,
 * и она же умеет забрать адрес прямо из контейнера. Neo4j и ClickHouse настраиваются
 * нашими собственными свойствами {@code app.stores.*}, о которых Boot ничего не знает, —
 * их проставляет {@link DynamicPropertyRegistrar}.
 *
 * <p>Регистратор — бин, а не статический {@code @DynamicPropertySource}, и это важно:
 * статический метод не может получить контейнеры, объявленные бинами. Контейнеры к моменту
 * его вызова уже запущены — Spring Boot стартует любой бин-{@code Startable} до того, как
 * до него доберутся зависимые бины.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StoresTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        return new MongoDBContainer("mongo:8.0");
    }

    @Bean
    Neo4jContainer neo4jContainer() {
        return new Neo4jContainer("neo4j:5.26").withAdminPassword(NEO4J_TEST_PASSWORD);
    }

    @Bean
    ClickHouseContainer clickHouseContainer() {
        return new ClickHouseContainer("clickhouse/clickhouse-server:25.3");
    }

    /**
     * Настройки трёх хранилищ, которые приложение конфигурирует само.
     *
     * <p>Имя дата-центра берётся у контейнера, а не пишется константой: Testcontainers
     * называет его {@code datacenter1}, но зависеть от этого совпадения не стоит —
     * при расхождении драйвер отвечает «нет доступных узлов», и причина совершенно неочевидна.
     */
    @Bean
    DynamicPropertyRegistrar storeProperties(
            Neo4jContainer neo4j,
            ClickHouseContainer clickHouse
    ) {
        return registry -> {
            registry.add("app.stores.neo4j.uri", neo4j::getBoltUrl);
            registry.add("app.stores.neo4j.username", () -> "neo4j");
            registry.add("app.stores.neo4j.password", () -> NEO4J_TEST_PASSWORD);

            registry.add("app.stores.clickhouse.url", clickHouse::getJdbcUrl);
            registry.add("app.stores.clickhouse.username", clickHouse::getUsername);
            registry.add("app.stores.clickhouse.password", clickHouse::getPassword);
        };
    }

    /** Пароль ниже восьми символов Neo4j не принимает и отказывается стартовать. */
    private static final String NEO4J_TEST_PASSWORD = "test-password";
}
