package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.restaurant.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Витрина ресторанов открыта всем, управление — только тому, кто рестораном управляет.
 */
@Slf4j
@RequiredArgsConstructor
@Validated
@RestController
@RequestMapping("/api/v1/restaurants")
@Tag(name = "Restaurant", description = "Витрина ресторанов и управление ими")
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping
    @Operation(summary = "Список ресторанов; доступен без входа")
    public List<RestaurantResponse> getAllRestaurants() {
        return restaurantService.getAllRestaurants();
    }

    @GetMapping("/{restaurantId}")
    @Operation(summary = "Ресторан по идентификатору; доступен без входа")
    public RestaurantResponse getRestaurantById(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        return restaurantService.getRestaurantById(restaurantId);
    }

    /**
     * Ресторана ещё нет, поэтому проверить управляющего нечем — здесь работает только роль.
     * Создатель становится управляющим внутри сервиса, в той же транзакции.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN')")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Создать ресторан; создатель становится его управляющим")
    public RestaurantResponse createRestaurant(@Valid @RequestBody CreateRestaurantRequest request) {
        log.info("Создание ресторана: name={}, city={}", request.name(), request.city());

        return restaurantService.createRestaurant(request);
    }

    @PatchMapping("/{restaurantId}")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Изменить данные ресторана; незаполненные поля не меняются")
    public RestaurantResponse updateRestaurant(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Valid @RequestBody UpdateRestaurantRequest request
    ) {
        log.info("Изменение ресторана: restaurantId={}", restaurantId);

        return restaurantService.updateRestaurant(restaurantId, request);
    }

    @PostMapping("/{restaurantId}/open")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Открыть приём заказов")
    public RestaurantResponse openRestaurant(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        log.info("Открытие ресторана: restaurantId={}", restaurantId);

        return restaurantService.setActive(restaurantId, true);
    }

    @PostMapping("/{restaurantId}/close")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Закрыть приём заказов; уже оформленные заказы это не отменяет")
    public RestaurantResponse closeRestaurant(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        log.info("Закрытие ресторана: restaurantId={}", restaurantId);

        return restaurantService.setActive(restaurantId, false);
    }
}
