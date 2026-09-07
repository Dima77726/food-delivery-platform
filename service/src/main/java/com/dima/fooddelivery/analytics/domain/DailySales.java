package com.dima.fooddelivery.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Продажи за день: сколько заказов оплачено и на какую сумму.
 *
 * <p>{@link LocalDate}, а не момент времени: день — это календарная единица, у неё нет
 * часа и смещения. Момент времени здесь означал бы полночь в каком-то поясе, и вопрос
 * "в каком" немедленно стал бы источником расхождений в отчётах.
 */
public record DailySales(
        LocalDate day,
        long orders,
        BigDecimal revenue,
        BigDecimal averageCheck
) {
}
