package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.CurrentUser;
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
 * <p>Сервис работает только с доменными типами и ничего не знает о сгенерированных из
 * спецификации моделях. Перевод в контракт делает контроллер. Благодаря этому смена
 * контракта не задевает бизнес-логику, а генератор не диктует сигнатуры сервисов.
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
    public List<Restaurant> getAllRestaurants() {
        return restaurantRepository.findAll();
    }

    /**
     * Создаёт ресторан и сразу назначает создателя управляющим.
     *
     * <p>Два действия слиты в одно намеренно. Если бы назначение было отдельным вызовом,
     * между созданием и назначением существовал бы ресторан без хозяина — и добраться до него
     * мог бы только администратор.
     */
    @Transactional
    public Restaurant createRestaurant(String name, String description, String city) {
        Long restaurantId = restaurantRepository.insert(name.trim(), description, city.trim());

        Long creatorId = currentUser.requireId();
        userService.assignRestaurantManager(creatorId, restaurantId);

        log.info("Создан ресторан: restaurantId={}, ownerId={}", restaurantId, creatorId);

        return requireRestaurant(restaurantId);
    }

    @Transactional
    public Restaurant updateRestaurant(Long restaurantId, String name, String description, String city) {
        requireRestaurant(restaurantId);

        restaurantRepository.update(restaurantId, trimOrNull(name), description, trimOrNull(city));

        log.info("Обновлён ресторан: restaurantId={}", restaurantId);

        return requireRestaurant(restaurantId);
    }

    /**
     * Открывает или закрывает приём заказов.
     *
     * <p>Уже оформленные заказы закрытие не трогает: ресторан обязан довести до конца то,
     * что взял. Перестаёт работать только добавление в корзину и создание новых заказов —
     * за этим следит {@link #requireActiveRestaurant(Long)}.
     */
    @Transactional
    public Restaurant setActive(Long restaurantId, boolean active) {
        requireRestaurant(restaurantId);

        restaurantRepository.setActive(restaurantId, active);

        log.info("Изменён приём заказов: restaurantId={}, active={}", restaurantId, active);

        return requireRestaurant(restaurantId);
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
