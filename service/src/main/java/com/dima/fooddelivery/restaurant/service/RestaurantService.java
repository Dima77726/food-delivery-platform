package com.dima.fooddelivery.restaurant.service;

import com.dima.fooddelivery.common.cache.CacheNames;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.restaurant.domain.Restaurant;
import com.dima.fooddelivery.restaurant.persistence.RestaurantRepository;
import com.dima.fooddelivery.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
 *
 * <p>Доступ к данным здесь идёт через Spring Data JPA, и главное отличие от соседних модулей
 * видно в методах изменения: ни одного UPDATE. Загруженная в транзакции сущность управляемая,
 * Hibernate помнит её исходное состояние и на коммите сам сравнивает его с текущим — это
 * называется грязной проверкой (dirty checking). Достаточно вызвать сеттер. Для сравнения,
 * в {@code OrderService} рядом каждый UPDATE написан руками и виден целиком.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final UserService userService;
    private final CurrentUser currentUser;

    /**
     * Витрина: самое частое чтение и единственный метод ресторана, который кэшируется.
     *
     * <p>Ключ константный, потому что список один на всех: параметров у метода нет,
     * а ключ по умолчанию для метода без аргументов и так был бы SimpleKey.EMPTY.
     * Явная константа читается понятнее в @CacheEvict.
     *
     * <p>В Redis уезжают отсоединённые (detached) сущности: транзакция к моменту сериализации
     * уже закрыта. Для ресторана это безопасно, потому что связей у него нет. Класть в кэш
     * сущность с ленивой коллекцией было бы миной — сериализатор дёрнул бы её за пределами
     * транзакции и получил LazyInitializationException.
     */
    @Cacheable(cacheNames = CacheNames.RESTAURANTS, key = "'all'")
    @Transactional(readOnly = true)
    public List<Restaurant> getAllRestaurants() {
        return restaurantRepository.findAllByOrderByIdAsc();
    }

    /**
     * Тот же список, но мимо кэша. Публичный вход для построителей производных моделей —
     * сейчас это переиндексация витрины в Elasticsearch.
     *
     * <p>Отдельный метод, а не {@link #getAllRestaurants()}, по той же причине, по которой
     * не кэшируется {@link #requireActiveRestaurant(Long)}: цена устаревших данных здесь
     * несопоставима с ценой лишнего запроса. Кэш живёт своей жизнью и вполне может отдать
     * ресторан, которого уже нет; витрина от этого покажет лишнюю строку и обновится через
     * минуту, а построитель индекса пойдёт за меню несуществующего ресторана и упадёт
     * на середине прохода.
     *
     * <p>Заодно это снимает связь, которой быть не должно: правильность поискового индекса
     * не может зависеть от того, когда истечёт запись в Redis.
     */
    @Transactional(readOnly = true)
    public List<Restaurant> findAllForProjection() {
        return restaurantRepository.findAllByOrderByIdAsc();
    }

    /**
     * Создаёт ресторан и сразу назначает создателя управляющим.
     *
     * <p>Два действия слиты в одно намеренно. Если бы назначение было отдельным вызовом,
     * между созданием и назначением существовал бы ресторан без хозяина — и добраться до него
     * мог бы только администратор.
     *
     * <p>Перечитывать созданное из базы, как делала JDBC-версия этого метода, больше не нужно:
     * {@code save} возвращает ту же сущность с проставленным идентификатором и отметками
     * времени из {@code @PrePersist}.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANTS, key = "'all'")
    @Transactional
    public Restaurant createRestaurant(String name, String description, String city) {
        Restaurant restaurant = restaurantRepository.save(
                new Restaurant(name.trim(), description, city.trim())
        );

        Long creatorId = currentUser.requireId();
        userService.assignRestaurantManager(creatorId, restaurant.getId());

        log.info("Создан ресторан: restaurantId={}, ownerId={}", restaurant.getId(), creatorId);

        return restaurant;
    }

    /**
     * Частичное обновление: null в параметре означает «не менять».
     *
     * <p>В JDBC-версии это же правило выражал COALESCE внутри UPDATE. Здесь оно стало
     * обычными условиями на языке, и разница не только в синтаксисе: правило видно там же,
     * где живёт остальная логика, а не внутри строки с SQL. Ограничение осталось прежним —
     * стереть описание в null через этот метод нельзя, для очистки клиент шлёт пустую строку.
     *
     * <p>{@code save} в конце не вызывается, и это не забывчивость. Сущность управляемая,
     * Hibernate заметит изменения сам и отправит UPDATE при сбросе контекста — на коммите
     * или перед ближайшим запросом к той же таблице.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANTS, key = "'all'")
    @Transactional
    public Restaurant updateRestaurant(Long restaurantId, String name, String description, String city) {
        // Вызов соседнего метода этого же класса идёт напрямую, минуя прокси Spring,
        // поэтому его readOnly = true здесь не применяется и сущность остаётся изменяемой.
        // Если бы readOnly сработал, Hibernate перевёл бы контекст в FlushMode.MANUAL
        // и правки молча не доехали бы до базы: ни ошибки, ни UPDATE.
        Restaurant restaurant = requireRestaurant(restaurantId);

        if (name != null) {
            restaurant.setName(name.trim());
        }

        if (description != null) {
            restaurant.setDescription(description);
        }

        if (city != null) {
            restaurant.setCity(city.trim());
        }

        log.info("Обновлён ресторан: restaurantId={}", restaurantId);

        return restaurant;
    }

    /**
     * Открывает или закрывает приём заказов.
     *
     * <p>Уже оформленные заказы закрытие не трогает: ресторан обязан довести до конца то,
     * что взял. Перестаёт работать только добавление в корзину и создание новых заказов —
     * за этим следит {@link #requireActiveRestaurant(Long)}.
     */
    @CacheEvict(cacheNames = CacheNames.RESTAURANTS, key = "'all'")
    @Transactional
    public Restaurant setActive(Long restaurantId, boolean active) {
        Restaurant restaurant = requireRestaurant(restaurantId);

        restaurant.setActive(active);

        log.info("Изменён приём заказов: restaurantId={}, active={}", restaurantId, active);

        return restaurant;
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
     * Намеренно не кэшируется, в отличие от витрины.
     *
     * <p>Этот метод решает, можно ли класть блюдо в корзину и создавать заказ. Устаревший
     * здесь признак active означает приём заказа закрытым рестораном — то есть еду, которую
     * никто не приготовит. Устаревшая витрина в худшем случае показывает лишнюю строчку
     * в списке; цена ошибки несопоставима.
     *
     * @throws ResourceNotFoundException      если ресторана нет
     * @throws BusinessRuleViolationException если ресторан не принимает заказы
     */
    @Transactional(readOnly = true)
    public Restaurant requireActiveRestaurant(Long restaurantId) {
        Restaurant restaurant = requireRestaurant(restaurantId);

        if (!restaurant.isActive()) {
            log.warn("Ресторан недоступен для заказов: restaurantId={}", restaurantId);

            throw new BusinessRuleViolationException(
                    "Ресторан с id=" + restaurantId + " сейчас недоступен для заказов"
            );
        }

        return restaurant;
    }
}
