package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.restaurant.api.CreateRestaurantRequest;
import com.dima.fooddelivery.restaurant.api.RestaurantResponse;
import com.dima.fooddelivery.restaurant.api.RestaurantResponseMapper;
import com.dima.fooddelivery.restaurant.api.UpdateRestaurantRequest;
import com.dima.fooddelivery.restaurant.domain.Restaurant;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRepository;
import com.dima.fooddelivery.user.service.UserService;
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
    private final UserService userService;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<RestaurantResponse> getAllRestaurants() {
        return RestaurantResponseMapper.toResponses(restaurantRepository.findAll());
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantById(Long restaurantId) {
        return RestaurantResponseMapper.toResponse(requireRestaurant(restaurantId));
    }

    /**
     * Создаёт ресторан и сразу назначает создателя управляющим.
     *
     * <p>Два действия слиты в одно намеренно. Если бы назначение было отдельным вызовом,
     * между созданием и назначением существовал бы ресторан без хозяина — и добраться до него
     * мог бы только администратор.
     */
    @Transactional
    public RestaurantResponse createRestaurant(CreateRestaurantRequest request) {
        Long restaurantId = restaurantRepository.insert(
                request.name().trim(),
                request.description(),
                request.city().trim()
        );

        Long creatorId = currentUser.requireId();
        userService.assignRestaurantManager(creatorId, restaurantId);

        log.info("Создан ресторан: restaurantId={}, ownerId={}", restaurantId, creatorId);

        return RestaurantResponseMapper.toResponse(requireRestaurant(restaurantId));
    }

    @Transactional
    public RestaurantResponse updateRestaurant(Long restaurantId, UpdateRestaurantRequest request) {
        requireRestaurant(restaurantId);

        int updated = restaurantRepository.update(
                restaurantId,
                trimOrNull(request.name()),
                request.description(),
                trimOrNull(request.city())
        );

        if (updated == 0) {
            throw new ResourceNotFoundException("Ресторан с id=" + restaurantId + " не найден");
        }

        log.info("Обновлён ресторан: restaurantId={}", restaurantId);

        return RestaurantResponseMapper.toResponse(requireRestaurant(restaurantId));
    }

    /**
     * Открывает или закрывает приём заказов.
     *
     * <p>Уже оформленные заказы закрытие не трогает: ресторан обязан довести до конца то,
     * что взял. Перестаёт работать только добавление в корзину и создание новых заказов —
     * за этим следит {@link #requireActiveRestaurant(Long)}.
     */
    @Transactional
    public RestaurantResponse setActive(Long restaurantId, boolean active) {
        requireRestaurant(restaurantId);

        restaurantRepository.setActive(restaurantId, active);

        log.info("Изменён приём заказов: restaurantId={}, active={}", restaurantId, active);

        return RestaurantResponseMapper.toResponse(requireRestaurant(restaurantId));
    }

    /**
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
     * @throws ResourceNotFoundException      если ресторана нет
     * @throws BusinessRuleViolationException если ресторан не принимает заказы
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

    private String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
