package com.dima.fooddelivery.order;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;

import java.util.List;

/**
 * Заказы глазами монолита: три вопроса к сервису заказов.
 *
 * <p>Интерфейс, а не gRPC-стаб в модулях: отзывам и треку незачем знать, что заказ живёт
 * в другом процессе. Исключения доменные — те же, что раньше бросал модуль Order монолита,
 * поэтому обработчик ошибок и коды ответов не поменялись.
 */
public interface OrderClient {

    /**
     * @throws ResourceNotFoundException    если заказа нет
     * @throws UpstreamUnavailableException если сервис заказов не ответил
     */
    Order requireOrder(Long orderId);

    /**
     * Заказы с идентификатором больше заданного, по возрастанию, вместе с отменёнными.
     * Пустой список — конец.
     *
     * @throws UpstreamUnavailableException если сервис заказов не ответил
     */
    List<Order> findAfter(long afterOrderId, int limit);

    /**
     * @throws ResourceNotFoundException    если доставки нет
     * @throws UpstreamUnavailableException если сервис заказов не ответил
     */
    Delivery requireDelivery(Long deliveryId);
}
