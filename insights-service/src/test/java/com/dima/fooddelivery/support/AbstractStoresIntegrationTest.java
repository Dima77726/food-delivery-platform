package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * База для тестов модулей на MongoDB, Neo4j и ClickHouse.
 *
 * <p>Контейнеров больше, чем у {@link AbstractIntegrationTest}, поэтому и контекст отдельный:
 * тестам без хранилищ незачем их поднимать. Контекст кэшируется, цена платится один раз.
 *
 * <p>Транзакций нет, и хранилища между тестами не очищаются. Поэтому каждый тест работает
 * с уникальными идентификаторами — см. {@link TestDataFactory}.
 *
 * <p>Фоновые задачи выключены. Проекция графа по таймеру меняла бы состояние посреди
 * проверки — тесты запускают её вручную.
 */
@SpringBootTest(properties = {
        "app.stores.mongo.enabled=true",
        "app.stores.neo4j.enabled=true",
        "app.stores.clickhouse.enabled=true",

        "app.stores.neo4j.projection-enabled=false",

        // Индикаторы здоровья привязаны к переменным окружения, а не к свойствам выше,
        // поэтому включаются отдельно. В тестах они нужны затем же, зачем в проде:
        // проверить, что при поднятых хранилищах приложение считает себя здоровым.
        "management.health.mongodb.enabled=true",

        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import({TestcontainersConfiguration.class, StoresTestcontainersConfiguration.class})
public abstract class AbstractStoresIntegrationTest {

    @Autowired
    protected TestDataFactory testData;
}
