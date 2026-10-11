package com.dima.fooddelivery.contracts.audit;

/**
 * Топик журнала аудита. Пишут в него все сервисы, где есть действия, требующие следа,
 * читает insights-service — в сводный журнал.
 *
 * <p>Ключ сообщения — ресурс ({@code ORDER:42}, {@code USER:7}): записи об одном ресурсе
 * идут по порядку, и история заказа в журнале не перемешается.
 */
public final class AuditTopics {

    public static final String AUDIT_EVENTS = "food-delivery.audit-events";

    private AuditTopics() {
    }
}
