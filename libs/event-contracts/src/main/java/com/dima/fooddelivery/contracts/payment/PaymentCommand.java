package com.dima.fooddelivery.contracts.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Команда сервису платежей от сервиса заказов.
 *
 * <p>Команда, а не событие: у неё ровно один адресат, и она просит сделать работу, а не
 * сообщает о сделанном. Отсюда и форма: сумма и способ оплаты едут внутри, чтобы сервису
 * платежей не пришлось спрашивать о них сервис заказов — синхронный вызов назад превратил бы
 * сагу в распределённую транзакцию с лишним шагом.
 *
 * <p>Типы команд — строки {@link #PAYMENT_REQUESTED} и {@link #REFUND_REQUESTED}, по тем же
 * причинам, что статусы в {@code OrderIntegrationEvent}: неизвестный тип потребитель может
 * пропустить, а неизвестное значение enum уронило бы десериализацию.
 *
 * <p>{@code commandId} — ключ идемпотентности: доставка «хотя бы один раз», и повтор команды
 * не должен списать деньги второй раз.
 *
 * @param method         способ оплаты; для возврата не заполняется
 * @param idempotencyKey ключ, присланный клиентом при оплате; для возврата не заполняется
 */
public record PaymentCommand(
        UUID commandId,
        int schemaVersion,
        String commandType,
        Long orderId,
        Long customerId,
        BigDecimal amount,
        String method,
        String idempotencyKey,
        OffsetDateTime occurredAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Списать оплату за заказ. */
    public static final String PAYMENT_REQUESTED = "PAYMENT_REQUESTED";

    /**
     * Вернуть деньги за заказ, если они были списаны. Если успешного платежа нет — например,
     * заказ отменили раньше, чем дошла команда на списание, — возвращать нечего, и это
     * не ошибка.
     */
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";
}
