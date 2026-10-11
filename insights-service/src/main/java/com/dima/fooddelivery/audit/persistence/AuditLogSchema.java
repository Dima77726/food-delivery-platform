package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.common.stores.ClickHouseJdbc;
import com.dima.fooddelivery.common.stores.StoreToggles;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Таблица сводного журнала аудита в ClickHouse. DDL на старте, как у витрины аналитики, и по
 * той же причине: таблица производная, её не мигрируют, а наполняют заново из топика.
 *
 * <p><b>Почему ClickHouse, а не PostgreSQL.</b> Журнал только растёт, никогда не правится
 * и читается выборками «что было с этим ресурсом» и «что делал этот человек». Ровно под такую
 * нагрузку колоночное хранилище и сделано, а сжатие повторяющихся строк — действие, тип
 * ресурса, сервис — делает хранение истории почти бесплатным.
 *
 * <p><b>ReplacingMergeTree</b> схлопывает повторную доставку той же записи. Копии одной
 * записи совпадают во всех колонках, и ключ сортировки у них один. Схлопываются они, как и в
 * витрине аналитики, только при фоновом слиянии — поэтому чтение идёт с {@code FINAL}
 * (см. {@link AuditLogRepository}).
 *
 * <p><b>Ключ сортировки</b> (resource_type, resource_id, occurred_at, event_id) — под главный
 * вопрос к журналу: история одного ресурса по времени. Поиск по автору или сервису читает
 * больше, и это приемлемо: журнал на порядки меньше витрины заказов.
 *
 * <p>{@code resource_id} — не Nullable: колонка в ключе сортировки, а Nullable в ключе
 * ClickHouse по умолчанию запрещает. Отсутствие ресурса хранится пустой строкой и
 * превращается обратно в {@code null} при чтении.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AuditLogSchema {

    public static final String TABLE = "audit_log";

    private final ClickHouseJdbc clickHouse;

    @PostConstruct
    void createTable() {
        clickHouse.template().execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    event_id       UUID,
                    source_service LowCardinality(String),
                    source_id      Int64,
                    actor_id       Nullable(Int64),
                    actor_email    Nullable(String),
                    action         LowCardinality(String),
                    resource_type  LowCardinality(String),
                    resource_id    String,
                    outcome        LowCardinality(String),
                    details        Nullable(String),
                    correlation_id Nullable(String),
                    occurred_at    DateTime64(3, 'UTC')
                )
                ENGINE = ReplacingMergeTree
                PARTITION BY toYYYYMM(occurred_at)
                ORDER BY (resource_type, resource_id, occurred_at, event_id)
                """.formatted(TABLE));

        log.info("Таблица ClickHouse {} готова", TABLE);
    }
}
