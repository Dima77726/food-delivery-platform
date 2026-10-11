package com.dima.fooddelivery.analytics.persistence;

import com.dima.fooddelivery.common.stores.ClickHouseJdbc;
import com.dima.fooddelivery.common.stores.StoreToggles;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Таблица витрины аналитики. DDL выполняется на старте.
 *
 * <p>Liquibase здесь не используется по той же причине, что и в Cassandra: витрина одна,
 * а главное — она производная. Потерянную витрину не восстанавливают миграциями, её
 * наполняют заново из топика, поэтому история изменений схемы ценности не имеет.
 *
 * <p><b>Движок ReplacingMergeTree.</b> Kafka доставляет "хотя бы один раз", и одно и то же
 * событие может приехать дважды. В PostgreSQL от этого защищает уникальный индекс, но
 * в ClickHouse уникальных индексов не существует вовсе - вставка никогда ничего не проверяет,
 * в этом и причина её скорости. ReplacingMergeTree схлопывает строки с одинаковым ключом
 * сортировки, но делает это в фоне, во время слияния кусков.
 *
 * <p>Практический вывод, из-за которого об этом важно знать: в любой момент в таблице могут
 * лежать дубликаты. Поэтому все запросы модуля написаны так, чтобы дубликаты на них
 * не влияли, — через uniqExact и DISTINCT, а не через count(). Полагаться на то, что слияние
 * уже прошло, нельзя: никакой гарантии, что оно вообще произойдёт сегодня, нет.
 *
 * <p><b>Ключ сортировки</b> (restaurant_id, occurred_at, event_id) — это и способ схлопывания
 * дубликатов, и разреженный индекс, по которому читаются запросы. Порядок полей соответствует
 * порядку фильтров: сначала ресторан, потом период. Запрос без ресторана прочтёт больше
 * данных, и это ожидаемо - витрина заточена под отчёт ресторана.
 *
 * <p><b>Партиционирование по месяцу</b> даёт дешёвое удаление истории: DROP PARTITION убирает
 * месяц целиком одним движением файловой системы, тогда как DELETE в ClickHouse — тяжёлая
 * перезапись кусков.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class OrderAnalyticsSchema {

    public static final String TABLE = "order_events";

    private final ClickHouseJdbc clickHouse;

    @PostConstruct
    void createTable() {
        clickHouse.template().execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    event_id        UUID,
                    event_type      LowCardinality(String),
                    order_id        UInt64,
                    customer_id     UInt64,
                    restaurant_id   UInt64,
                    previous_status LowCardinality(String),
                    new_status      LowCardinality(String),
                    total_amount    Decimal(12, 2),
                    occurred_at     DateTime64(3, 'UTC')
                )
                ENGINE = ReplacingMergeTree
                PARTITION BY toYYYYMM(occurred_at)
                ORDER BY (restaurant_id, occurred_at, event_id)
                """.formatted(TABLE));

        log.info("Таблица ClickHouse {} готова", TABLE);
    }
}
