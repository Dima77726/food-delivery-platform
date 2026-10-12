package com.dima.fooddelivery.common.security;

/**
 * Права на рестораны — у того, кто владеет пользователями.
 *
 * <p>Интерфейс, а не прямые вызовы gRPC-стаба по коду: сервисам каталога не нужно знать,
 * каким протоколом и куда уходит вопрос. Когда пользователи переедут в identity-service,
 * поменяется адрес в конфигурации, а не этот контракт. Заодно тестам каталога не нужен
 * настоящий монолит — достаточно подменить реализацию.
 */
public interface RestaurantAccessClient {

    /**
     * @throws UpstreamUnavailableException если владелец прав не ответил
     */
    boolean managesRestaurant(long userId, long restaurantId);

    /**
     * Идемпотентно: повторное назначение — не ошибка.
     *
     * @throws UpstreamUnavailableException если владелец прав не ответил
     */
    void assignManager(long userId, long restaurantId);
}
