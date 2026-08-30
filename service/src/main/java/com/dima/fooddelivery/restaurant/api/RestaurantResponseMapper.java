package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.generated.model.Restaurant;

import java.util.List;

/**
 * Перевод доменного типа в модель контракта.
 *
 * <p>Обратите внимание на два одноимённых класса: {@code restaurant.domain.Restaurant} —
 * наша сущность Hibernate, {@code generated.model.Restaurant} — сгенерированная модель ответа.
 * Совпадение имён не случайно и не мешает: они живут в разных пакетах и меняются по разным
 * причинам. Доменный тип — вслед за предметной областью, модель контракта — вслед за спекой.
 * Именно этот шов и позволяет менять внутреннее устройство, не ломая клиентов: переезд
 * этого модуля с JDBC на JPA не потребовал ни одной правки в спецификации.
 */
public final class RestaurantResponseMapper {

    public static Restaurant toResponse(com.dima.fooddelivery.restaurant.domain.Restaurant restaurant) {
        return new Restaurant()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .description(restaurant.getDescription())
                .city(restaurant.getCity())
                .active(restaurant.isActive());
    }

    public static List<Restaurant> toResponses(
            List<com.dima.fooddelivery.restaurant.domain.Restaurant> restaurants
    ) {
        return restaurants.stream().map(RestaurantResponseMapper::toResponse).toList();
    }

    private RestaurantResponseMapper() {
    }
}
