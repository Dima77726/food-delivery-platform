package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.generated.api.RestaurantApi;
import com.dima.fooddelivery.generated.model.CreateRestaurantRequest;
import com.dima.fooddelivery.generated.model.Restaurant;
import com.dima.fooddelivery.generated.model.UpdateRestaurantRequest;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Реализация сгенерированного контракта {@link RestaurantApi}.
 *
 * <p>Ни путей, ни HTTP-методов, ни кодов ответа здесь нет — всё это описано в
 * {@code openapi/food-delivery-api.yaml} и приезжает вместе с интерфейсом. Поменять маршрут,
 * не тронув спеку, теперь невозможно: сборка не даст.
 *
 * <p>Права остались здесь. Спецификация описывает контракт, а не политику доступа: то, что
 * эндпоинт защищён, в ней помечено схемой безопасности, но кто именно вправе — решает
 * приложение.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class RestaurantController implements RestaurantApi {

    private final RestaurantService restaurantService;

    @Override
    public ResponseEntity<List<Restaurant>> listRestaurants() {
        return ResponseEntity.ok(RestaurantResponseMapper.toResponses(restaurantService.getAllRestaurants()));
    }

    @Override
    public ResponseEntity<Restaurant> getRestaurant(Long restaurantId) {
        return ResponseEntity.ok(
                RestaurantResponseMapper.toResponse(restaurantService.requireRestaurant(restaurantId))
        );
    }

    /**
     * Ресторана ещё нет, поэтому проверить управляющего нечем — здесь работает только роль.
     * Создатель становится управляющим внутри сервиса, в той же транзакции.
     */
    @Override
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    public ResponseEntity<Restaurant> createRestaurant(CreateRestaurantRequest request) {
        log.info("Создание ресторана: name={}, city={}", request.getName(), request.getCity());

        Restaurant created = RestaurantResponseMapper.toResponse(
                restaurantService.createRestaurant(request.getName(), request.getDescription(), request.getCity())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    public ResponseEntity<Restaurant> updateRestaurant(Long restaurantId, UpdateRestaurantRequest request) {
        log.info("Изменение ресторана: restaurantId={}", restaurantId);

        return ResponseEntity.ok(RestaurantResponseMapper.toResponse(
                restaurantService.updateRestaurant(
                        restaurantId, request.getName(), request.getDescription(), request.getCity()
                )
        ));
    }

    @Override
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    public ResponseEntity<Restaurant> openRestaurant(Long restaurantId) {
        log.info("Открытие ресторана: restaurantId={}", restaurantId);

        return ResponseEntity.ok(
                RestaurantResponseMapper.toResponse(restaurantService.setActive(restaurantId, true))
        );
    }

    @Override
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    public ResponseEntity<Restaurant> closeRestaurant(Long restaurantId) {
        log.info("Закрытие ресторана: restaurantId={}", restaurantId);

        return ResponseEntity.ok(
                RestaurantResponseMapper.toResponse(restaurantService.setActive(restaurantId, false))
        );
    }
}
