package com.dima.fooddelivery.common.stores;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки подключения к ClickHouse.
 *
 * @param enabled        включён ли модуль аналитики
 * @param url            JDBC-адрес вида jdbc:clickhouse://host:8123/база
 * @param username       пользователь
 * @param password       пароль
 * @param poolSize       размер пула соединений
 * @param queryTimeout   предел на выполнение одного запроса
 * @param consumerGroup  группа потребителя Kafka, из которой наполняется витрина
 */
@ConfigurationProperties(prefix = "app.stores.clickhouse")
public record ClickHouseStoreProperties(
        boolean enabled,
        String url,
        String username,
        String password,
        Integer poolSize,
        Duration queryTimeout,
        String consumerGroup
) {

    public ClickHouseStoreProperties {
        url = url == null ? "jdbc:clickhouse://localhost:8123/food_delivery" : url;
        // Учётные данные обязательны и приходят из переменных окружения: значения
        // по умолчанию означали бы пароль в репозитории.
        username = required(username, "CLICKHOUSE_USER");
        password = required(password, "CLICKHOUSE_PASSWORD");
        // Аналитике не нужен большой пул: пишет её один потребитель Kafka пачками,
        // читают редкие отчёты. Четырёх соединений хватает с запасом, а лишние соединения
        // к ClickHouse обходятся дороже, чем к PostgreSQL: каждое из них при запросе
        // способно занять заметную долю памяти узла.
        poolSize = poolSize == null ? 4 : poolSize;
        queryTimeout = queryTimeout == null ? Duration.ofSeconds(10) : queryTimeout;
        consumerGroup = consumerGroup == null ? "food-delivery-analytics" : consumerGroup;
    }

    private static String required(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Не задана переменная окружения " + variable);
        }
        return value;
    }
}
