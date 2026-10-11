package com.dima.fooddelivery.catalog;

import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;

import java.util.Optional;

/**
 * Каталог глазами insights-service: один вопрос — что это за ресторан.
 *
 * <p>Нужен GraphQL: экран ресторана показывает название и город рядом с рейтингом и отзывами,
 * а они живут в каталоге. Ресторан в любом статусе — отзывы закрытого читать можно.
 */
public interface CatalogClient {

    /**
     * @return ресторан или пустое значение, если его нет
     * @throws UpstreamUnavailableException если каталог не ответил
     */
    Optional<RestaurantInfo> findRestaurant(Long restaurantId);

    record RestaurantInfo(Long id, String name, String city, boolean active) {
    }
}
