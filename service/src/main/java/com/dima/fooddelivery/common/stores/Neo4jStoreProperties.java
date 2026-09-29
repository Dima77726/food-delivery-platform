package com.dima.fooddelivery.common.stores;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки подключения к Neo4j.
 *
 * @param enabled            включён ли модуль рекомендаций
 * @param uri                адрес bolt-протокола
 * @param username           пользователь
 * @param password           пароль
 * @param projectionInterval как часто проекция догоняет заказы из PostgreSQL
 * @param projectionBatch    сколько заказов проецируется за один проход
 * @param projectionEnabled  запускать ли фоновую проекцию по расписанию
 */
@ConfigurationProperties(prefix = "app.stores.neo4j")
public record Neo4jStoreProperties(
        boolean enabled,
        String uri,
        String username,
        String password,
        Duration projectionInterval,
        Integer projectionBatch,
        Boolean projectionEnabled
) {

    public Neo4jStoreProperties {
        uri = uri == null ? "bolt://localhost:7687" : uri;
        username = username == null ? "neo4j" : username;
        password = password == null ? "food_delivery_neo4j" : password;
        projectionInterval = projectionInterval == null ? Duration.ofSeconds(30) : projectionInterval;
        // Пачка небольшая намеренно: проекция догоняет отставание за несколько проходов,
        // зато каждый проход короткий и не держит соединение к PostgreSQL полминуты.
        projectionBatch = projectionBatch == null ? 200 : projectionBatch;
        projectionEnabled = projectionEnabled == null ? Boolean.TRUE : projectionEnabled;
    }
}
