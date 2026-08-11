package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import com.dima.fooddelivery.restaurant.api.RestaurantResponseMapper;
import com.dima.fooddelivery.restaurant.domain.Restaurant;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Модуль Restaurant.
 *
 * <p>Публичный вход для других модулей — {@link #requireActiveRestaurant(Long)}. Модуль Cart
 * обращается сюда, а не в таблицу {@code restaurant} напрямую: правило «в закрытом ресторане
 * нельзя заказывать» принадлежит этому модулю и должно меняться в одном месте.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;

    @Transactional(readOnly = true)
    public List<RestaurantResponse> getAllRestaurants() {
        List<Restaurant> restaurants = restaurantRepository.findAll();

        log.debug("Список ресторанов загружен: restaurantsCount={}", restaurants.size());

        return RestaurantResponseMapper.toResponses(restaurants);
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantById(Long restaurantId) {
        Restaurant restaurant = requireRestaurant(restaurantId);

        return RestaurantResponseMapper.toResponse(restaurant);
    }

    /**
     * Возвращает ресторан или бросает 404.
     *
     * @throws ResourceNotFoundException если ресторана нет
     */
    @Transactional(readOnly = true)
    public Restaurant requireRestaurant(Long restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> {
                    log.warn("Ресторан не найден: restaurantId={}", restaurantId);
                    return new ResourceNotFoundException("Ресторан с id=" + restaurantId + " не найден");
                });
    }

    /**
     * Проверяет, что ресторан существует и принимает заказы.
     *
     * @throws ResourceNotFoundException      если ресторана нет
     * @throws BusinessRuleViolationException если ресторан отключён
     */
    @Transactional(readOnly = true)
    public Restaurant requireActiveRestaurant(Long restaurantId) {
        Restaurant restaurant = requireRestaurant(restaurantId);

        if (!restaurant.active()) {
            log.warn("Ресторан недоступен для заказов: restaurantId={}", restaurantId);

            throw new BusinessRuleViolationException(
                    "Ресторан с id=" + restaurantId + " сейчас недоступен для заказов"
            );
        }

        return restaurant;
    }
}
