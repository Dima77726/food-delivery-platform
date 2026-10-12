package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.security.RestaurantAccessClient;
import com.dima.fooddelivery.common.security.UpstreamUnavailableException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Права на рестораны в памяти — вместо gRPC-вызова в монолит.
 *
 * <p>Ведёт себя как настоящий владелец прав в том, что важно каталогу: назначение
 * идемпотентно, проверка видит назначенное. Контекст Spring общий для тестов, поэтому
 * и множество общее — изоляцию дают уникальные идентификаторы пользователей и ресторанов.
 */
public class InMemoryRestaurantAccessClient implements RestaurantAccessClient {

    private final Set<String> grants = ConcurrentHashMap.newKeySet();

    private volatile boolean unavailable;

    /**
     * Имитация недоступного владельца прав: вызовы бросают то же исключение, что настоящий
     * gRPC-клиент при сбое сети. Тест обязан вернуть выключатель в {@code finally} —
     * контекст, а с ним и этот объект, общий для всех тестов.
     */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public boolean managesRestaurant(long userId, long restaurantId) {
        failIfUnavailable();
        return grants.contains(key(userId, restaurantId));
    }

    @Override
    public void assignManager(long userId, long restaurantId) {
        failIfUnavailable();
        grants.add(key(userId, restaurantId));
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new UpstreamUnavailableException("Сервис пользователей недоступен (имитация)", null);
        }
    }

    private String key(long userId, long restaurantId) {
        return userId + ":" + restaurantId;
    }
}
