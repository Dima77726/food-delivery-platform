package com.dima.fooddelivery.contracts.payment;

/**
 * Топики саги оплаты между order-service и payment-service.
 *
 * <p>Два топика, а не один, потому что у них разные хозяева и разное направление:
 * команды пишет только сервис заказов и читает только сервис платежей, события — наоборот.
 * Один общий топик заставил бы каждую сторону вычитывать и выбрасывать собственные сообщения.
 *
 * <p>Ключ сообщения в обоих — идентификатор заказа. Это не удобство, а условие корректности:
 * «спиши» и «верни» одного заказа обязаны прийти к сервису платежей в том порядке, в каком
 * их отправили, иначе возврат обогнал бы списание и не нашёл бы, что возвращать.
 */
public final class PaymentTopics {

    /** Команды сервису платежей: {@link PaymentCommand}. */
    public static final String PAYMENT_COMMANDS = "food-delivery.payment-commands";

    /** Исходы платежей: {@link PaymentEvent}. */
    public static final String PAYMENT_EVENTS = "food-delivery.payment-events";

    private PaymentTopics() {
    }
}
