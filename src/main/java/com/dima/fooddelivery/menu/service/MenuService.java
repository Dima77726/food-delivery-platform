package com.dima.fooddelivery.menu.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.api.MenuResponseMapper;
import com.dima.fooddelivery.menu.api.RestaurantMenuResponse;
import com.dima.fooddelivery.menu.domain.MenuItemSnapshot;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.menu.persistence.MenuRepository;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Модуль Menu.
 *
 * <p>Публичный вход для других модулей — {@link #requireAvailableItem(Long, Long)}: модуль Cart
 * спрашивает здесь цену и доступность блюда вместо того, чтобы join'ить {@code menu_item}
 * у себя.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;
    private final RestaurantService restaurantService;

    @Transactional(readOnly = true)
    public RestaurantMenuResponse getRestaurantMenu(Long restaurantId) {
        // Меню закрытого ресторана показать можно, поэтому проверяется только существование.
        restaurantService.requireRestaurant(restaurantId);

        RestaurantMenu menu = menuRepository.findMenuByRestaurantId(restaurantId);

        log.debug(
                "Меню ресторана загружено: restaurantId={}, categoriesCount={}",
                restaurantId,
                menu.categories().size()
        );

        return MenuResponseMapper.toResponse(menu);
    }

    /**
     * Блюдо, доступное к заказу в этом ресторане.
     *
     * @throws ResourceNotFoundException      если блюда нет в этом ресторане
     * @throws BusinessRuleViolationException если блюдо есть, но помечено недоступным
     */
    @Transactional(readOnly = true)
    public MenuItemSnapshot requireAvailableItem(Long restaurantId, Long menuItemId) {
        MenuItemSnapshot item = menuRepository.findItemInRestaurant(restaurantId, menuItemId)
                .orElseThrow(() -> {
                    log.warn(
                            "Позиция меню не найдена в ресторане: restaurantId={}, menuItemId={}",
                            restaurantId,
                            menuItemId
                    );

                    return new ResourceNotFoundException(
                            "Позиция меню с id=" + menuItemId + " не найдена в ресторане с id=" + restaurantId
                    );
                });

        if (!item.available()) {
            log.warn(
                    "Позиция меню недоступна для заказа: restaurantId={}, menuItemId={}",
                    restaurantId,
                    menuItemId
            );

            throw new BusinessRuleViolationException(
                    "Позиция меню с id=" + menuItemId + " сейчас недоступна для заказа"
            );
        }

        return item;
    }
}
