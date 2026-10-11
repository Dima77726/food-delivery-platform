package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.audit.domain.AuditFilter;
import com.dima.fooddelivery.audit.domain.AuditRecord;
import com.dima.fooddelivery.common.stores.ClickHouseJdbc;
import com.dima.fooddelivery.common.stores.StoreToggles;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сводный журнал аудита в ClickHouse.
 *
 * <p>Вставка пачками и время строкой в UTC — по тем же причинам, что в
 * {@code OrderAnalyticsRepository}. Отличается чтение.
 *
 * <p><b>{@code FINAL} вместо {@code uniqExact} и {@code DISTINCT}.</b> Витрина аналитики
 * отвечает агрегатами, и дубликаты гасятся прямо в агрегатной функции. Журнал отвечает
 * строками, и каждая копия записи была бы лишней строкой в ответе. {@code FINAL} схлопывает
 * копии при чтении — так, как их схлопнуло бы слияние, если бы оно уже прошло. Это дороже
 * обычного чтения, но журнал невелик, запрос ограничен лимитом, а копии одной записи лежат
 * в одной партиции — время действия у них общее.
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AuditLogRepository {

    private static final DateTimeFormatter CLICKHOUSE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final String INSERT = """
            INSERT INTO %s (
                event_id, source_service, source_id, actor_id, actor_email, action,
                resource_type, resource_id, outcome, details, correlation_id, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(AuditLogSchema.TABLE);

    /**
     * Время читается миллисекундами от эпохи: так оно не проходит через календарь JVM
     * и не зависит от того, во что драйвер решит превратить DateTime64.
     */
    private static final String SELECT = """
            SELECT
                toString(event_id) AS event_id_text,
                source_service, source_id, actor_id, actor_email, action,
                resource_type, resource_id, outcome, details, correlation_id,
                toUnixTimestamp64Milli(occurred_at) AS occurred_at_ms
            FROM %s FINAL
            WHERE 1 = 1
            """.formatted(AuditLogSchema.TABLE);

    /**
     * Свежие сверху. Внутри одной миллисекунды порядок держит номер записи в источнике —
     * он растёт вместе с журналом сервиса.
     */
    private static final String ORDER_AND_LIMIT = """
            ORDER BY occurred_at DESC, source_service, source_id DESC
            LIMIT ?
            """;

    private static final RowMapper<AuditRecord> AUDIT_RECORD = (rs, rowNum) -> {
        // У Nullable-колонок getLong вернул бы 0 вместо NULL: системное действие оказалось
        // бы записанным на пользователя с номером ноль. wasNull различает одно и другое.
        long actorId = rs.getLong("actor_id");
        Long actor = rs.wasNull() ? null : actorId;

        String resourceId = rs.getString("resource_id");

        return new AuditRecord(
                UUID.fromString(rs.getString("event_id_text")),
                rs.getString("source_service"),
                rs.getLong("source_id"),
                actor,
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("resource_type"),
                resourceId == null || resourceId.isEmpty() ? null : resourceId,
                rs.getString("outcome"),
                rs.getString("details"),
                rs.getString("correlation_id"),
                Instant.ofEpochMilli(rs.getLong("occurred_at_ms"))
        );
    };

    private final ClickHouseJdbc clickHouse;

    /** @return сколько строк ушло в ClickHouse */
    public int insertBatch(List<AuditRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }

        clickHouse.template().batchUpdate(INSERT, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                AuditRecord record = records.get(index);

                statement.setString(1, record.eventId().toString());
                statement.setString(2, record.sourceService());
                statement.setLong(3, record.sourceId());

                if (record.actorId() == null) {
                    statement.setNull(4, Types.BIGINT);
                } else {
                    statement.setLong(4, record.actorId());
                }

                setNullableString(statement, 5, record.actorEmail());
                statement.setString(6, record.action());
                statement.setString(7, record.resourceType());
                statement.setString(8, record.resourceId() == null ? "" : record.resourceId());
                statement.setString(9, record.outcome());
                setNullableString(statement, 10, record.details());
                setNullableString(statement, 11, record.correlationId());
                statement.setString(12, CLICKHOUSE_TIMESTAMP.format(record.occurredAt()));
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });

        return records.size();
    }

    /**
     * Последние записи по фильтру.
     *
     * <p>Условия — постоянные фрагменты SQL, значения — параметры. Текст запроса зависит только
     * от того, какие поля фильтра заданы, но не от их содержимого.
     */
    public List<AuditRecord> find(AuditFilter filter, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>(5);

        if (filter.sourceService() != null) {
            sql.append("  AND source_service = ?\n");
            args.add(filter.sourceService());
        }

        if (filter.resourceType() != null) {
            sql.append("  AND resource_type = ?\n");
            args.add(filter.resourceType());
        }

        if (filter.resourceId() != null) {
            sql.append("  AND resource_id = ?\n");
            args.add(filter.resourceId());
        }

        if (filter.actorId() != null) {
            sql.append("  AND actor_id = ?\n");
            args.add(filter.actorId());
        }

        sql.append(ORDER_AND_LIMIT);
        args.add(limit);

        return clickHouse.template().query(sql.toString(), AUDIT_RECORD, args.toArray());
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
