package com.dima.fooddelivery.recommendation.api;

/**
 * Результат ручного запуска проекции графа.
 *
 * @param ordersRead сколько заказов прочитано из PostgreSQL за этот запуск
 */
public record ProjectionResultResponse(int ordersRead) {
}
