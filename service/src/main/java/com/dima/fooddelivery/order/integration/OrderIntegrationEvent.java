package com.dima.fooddelivery.order.integration;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Событие заказа, публикуемое в Kafka.
 *
 * <p>Это <b>контракт</b>, а не внутренний тип. Его читают чужие потребители, которых мы
 * не контролируем и не можем пересобрать вместе с собой. Отсюда несколько правил, которые
 * не действуют для {@code OrderStatusChangedEvent} — тот живёт внутри процесса и меняется
 * свободно.
 *
 * <p><b>Поля только добавляются.</b> Удаление поля или смена его типа ломает потребителей,
 * которые уже читают топик. Если поле стало не нужно — оно просто перестаёт заполняться.
 *
 * <p><b>Статусы передаются строками, а не enum'ом.</b> Enum на стороне потребителя развалится
 * на неизвестном значении, стоит нам добавить новый статус. Строка позволяет ему игнорировать
 * то, чего он не знает.
 *
 * <p><b>{@code schemaVersion}</b> нужен для несовместимых изменений: когда их не избежать,
 * потребитель сможет отличить старый формат от нового, а не гадать по набору полей.
 *
 * <p><b>{@code eventId}</b> — ключ идемпотентности. Outbox гарантирует доставку «хотя бы
 * один раз», то есть дубликаты возможны, и отсеивать их обязан потребитель.
 */
public record OrderIntegrationEvent(
        UUID eventId,
        int schemaVersion,
        String eventType,
        Long orderId,
        Long customerId,
        Long restaurantId,
        String previousStatus,
        String newStatus,
        BigDecimal totalAmount,
        OffsetDateTime occurredAt
) {

    /** Текущая версия схемы. Поднимается только при несовместимом изменении. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
