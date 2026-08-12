package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.generated.model.Restaurant;

import java.util.List;

/**
 * Перевод доменного типа в модель контракта.
 *
 * <p>Обратите внимание на два одноимённых класса: {@code restaurant.domain.Restaurant} —
 * наш доменный record, {@code generated.model.Restaurant} — сгенерированная модель ответа.
 * Совпадение имён не случайно и не мешает: они живут в разных пакетах и меняются по разным
 * причинам. Доменный тип — вслед за предметной областью, модель контракта — вслед за спекой.
 * Именно этот шов и позволяет менять внутреннее устройство, не ломая клиентов.
 */
public final class RestaurantResponseMapper {

    public static Restaurant toResponse(com.dima.fooddelivery.restaurant.domain.Restaurant restaurant) {
        return new Restaurant()
                .id(restaurant.id())
                .name(restaurant.name())
                .description(restaurant.description())
                .city(restaurant.city())
                .active(restaurant.active());
    }

    public static List<Restaurant> toResponses(
            List<com.dima.fooddelivery.restaurant.domain.Restaurant> restaurants
    ) {
        return restaurants.stream().map(RestaurantResponseMapper::toResponse).toList();
    }

    private RestaurantResponseMapper() {
    }
}
