package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * База для тестов пяти дополнительных хранилищ.
 *
 * <p>Восемь контейнеров на один контекст: PostgreSQL, Redis и Kafka нужны самому приложению,
 * остальные пять — модулям, которые здесь проверяются. Контекст один на все такие тесты
 * и кэшируется, поэтому цена платится однажды за сборку.
 *
 * <p><b>Почему это отдельная база, а не свойства в {@link AbstractIntegrationTest}.</b>
 * Иначе тяжёлые контейнеры поднимались бы и для тестов SQL, которым они не нужны,
 * и быстрая обратная связь по основной логике исчезла бы.
 *
 * <p><b>{@link Transactional} здесь работает только наполовину, и об этом важно помнить.</b>
 * Откат после теста возвращает в исходное состояние PostgreSQL — и только его. Ни MongoDB,
 * ни ClickHouse, ни Neo4j о транзакции приложения не знают
 * и записанное не отменят. Поэтому каждый тест обязан либо работать с уникальными
 * идентификаторами, либо убирать за собой сам. Первое надёжнее: идентификаторы заказов
 * и ресторанов приходят из последовательностей PostgreSQL, а те откатом не сбрасываются,
 * то есть уникальны и между тестами.
 *
 * <p>Фоновые задачи выключены все до единой. Проекция графа по таймеру
 * означала бы, что состояние хранилища меняется посреди проверки, — тесты запускают
 * её вручную.
 */
@SpringBootTest(properties = {
        "app.outbox.publish-enabled=false",

        "app.stores.mongo.enabled=true",
        "app.stores.neo4j.enabled=true",
        "app.stores.clickhouse.enabled=true",

        "app.stores.neo4j.projection-enabled=false",

        // Индикаторы здоровья привязаны к переменным окружения, а не к свойствам выше,
        // поэтому включаются отдельно. В тестах они нужны затем же, зачем в проде:
        // проверить, что при поднятых хранилищах приложение считает себя здоровым.
        "management.health.mongodb.enabled=true",

        "logging.level.liquibase=WARN",
        "logging.level.net.lbruun.springboot.preliquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import({TestcontainersConfiguration.class, StoresTestcontainersConfiguration.class})
@Transactional
public abstract class AbstractStoresIntegrationTest {

    @Autowired
    protected TestDataFactory testData;
}
