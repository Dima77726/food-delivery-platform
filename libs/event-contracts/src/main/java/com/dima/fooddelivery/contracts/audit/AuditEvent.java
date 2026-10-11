package com.dima.fooddelivery.contracts.audit;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Запись журнала аудита, публикуемая сервисом, где произошло действие.
 *
 * <p>Каждый сервис пишет аудит у себя, в той же транзакции, что и само действие, — иначе
 * возможна запись без действия или действие без записи. В Kafka запись уезжает потом, и её
 * собирает в один журнал insights-service. Так сводный журнал не требует общей базы.
 *
 * <p>{@code eventId} выводится из пары «сервис — номер записи» и потому одинаков при повторной
 * отправке: потребитель отсеивает дубликаты по нему. Остальные правила — как у прочих
 * контрактов: поля только добавляются, перечисления — строками.
 *
 * @param sourceService имя сервиса, где записано действие: {@code order-service}, {@code identity-service}
 * @param sourceId      номер записи в журнале этого сервиса
 * @param actorId       кто выполнил действие; {@code null} — система (например, отмена по таймауту)
 * @param outcome       {@code SUCCESS} или {@code FAILURE}
 */
public record AuditEvent(
        UUID eventId,
        int schemaVersion,
        String sourceService,
        Long sourceId,
        Long actorId,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String details,
        String correlationId,
        OffsetDateTime occurredAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Идентификатор события по паре «сервис — номер записи»: повтор отправки даёт тот же. */
    public static UUID eventIdOf(String sourceService, long sourceId) {
        return UUID.nameUUIDFromBytes((sourceService + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
    }
}
