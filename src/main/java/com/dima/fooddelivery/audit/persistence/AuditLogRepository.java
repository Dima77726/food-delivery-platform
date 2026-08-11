package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.audit.domain.AuditEntry;
import com.dima.fooddelivery.audit.domain.AuditOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Журнал аудита. Только INSERT и SELECT — методов обновления и удаления нет намеренно:
 * журнал, который можно поправить, ничего не доказывает.
 */
@Repository
@RequiredArgsConstructor
public class AuditLogRepository {

    private static final RowMapper<AuditEntry> AUDIT_ENTRY = (rs, rowNum) -> new AuditEntry(
            rs.getLong("id"),
            rs.getObject("actor_id", Long.class),
            rs.getString("actor_email"),
            rs.getString("action"),
            rs.getString("resource_type"),
            rs.getString("resource_id"),
            AuditOutcome.fromDbValue(rs.getString("outcome")),
            rs.getString("details"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private static final String SELECT_ENTRY = """
            SELECT a.id, a.actor_id, a.actor_email, a.action, a.resource_type,
                   a.resource_id, a.outcome, a.details, a.created_at
            FROM audit_log a
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Long append(
            Long actorId,
            String actorEmail,
            String action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome,
            String details
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("actorEmail", actorEmail)
                .addValue("action", action)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("outcome", outcome.name())
                .addValue("details", details);

        return jdbc.queryForObject(
                """
                        INSERT INTO audit_log (
                            actor_id, actor_email, action, resource_type, resource_id, outcome, details
                        )
                        VALUES (:actorId, :actorEmail, :action, :resourceType, :resourceId, :outcome, :details)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public List<AuditEntry> findRecent(int limit) {
        return jdbc.query(
                SELECT_ENTRY + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                AUDIT_ENTRY
        );
    }

    public List<AuditEntry> findByActor(Long actorId, int limit) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("limit", limit);

        return jdbc.query(
                SELECT_ENTRY + " WHERE a.actor_id = :actorId ORDER BY a.created_at DESC, a.id DESC LIMIT :limit",
                params,
                AUDIT_ENTRY
        );
    }

    public List<AuditEntry> findByResource(String resourceType, String resourceId, int limit) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("limit", limit);

        return jdbc.query(
                SELECT_ENTRY + """
                         WHERE a.resource_type = :resourceType
                           AND a.resource_id = :resourceId
                         ORDER BY a.created_at DESC, a.id DESC
                         LIMIT :limit
                        """,
                params,
                AUDIT_ENTRY
        );
    }
}
