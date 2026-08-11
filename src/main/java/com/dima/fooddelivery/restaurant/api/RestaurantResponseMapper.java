package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.restaurant.domain.Restaurant;

import java.util.List;

/**
 * Перевод доменных типов в DTO ответа.
 *
 * <p>Маппинг живёт в пакете {@code api}, а не в сервисе, потому что это часть контракта HTTP:
 * когда меняется форма ответа, правится только этот класс, а сервис и репозиторий не трогаются.
 */
public final class RestaurantResponseMapper {

    public static RestaurantResponse toResponse(Restaurant restaurant) {
        return new RestaurantResponse(
                restaurant.id(),
                restaurant.name(),
                restaurant.description(),
                restaurant.city(),
                restaurant.active()
        );
    }

    public static List<RestaurantResponse> toResponses(List<Restaurant> restaurants) {
        return restaurants.stream().map(RestaurantResponseMapper::toResponse).toList();
    }

    private RestaurantResponseMapper() {
    }
}
