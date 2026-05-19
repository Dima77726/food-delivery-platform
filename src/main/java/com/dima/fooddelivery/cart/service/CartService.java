package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartItemResponse;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.api.UpdateCartItemQuantityRequest;
import com.dima.fooddelivery.cart.persistence.CartRow;
import com.dima.fooddelivery.cart.persistence.CartRowMapper;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CartService {

    private final JdbcTemplate jdbcTemplate;

    private final CartRowMapper cartRowMapper;

    //метод ищет активную корзину клиента по ресторану
    @Transactional(readOnly = true)
    public CartResponse getActiveCart(Long customerId, Long restaurantId) {
        log.info(
                "Начинаем загрузку активной корзины: customerId={}, restaurantId={}",
                customerId,
                restaurantId
        );

        String sql = """
                SELECT 
                    c.id as cart_id,
                    c.customer_id,
                    c.restaurant_id,
                    c.status as cart_status,
                    
                    ci.id as cart_item_id,
                    ci.menu_item_id as menu_item_id,
                    mi.name as menu_item_name,
                    ci.quantity,
                    ci.price
                from food_app.cart c 
                left join food_app.cart_item ci on c.id = ci.cart_id
                left join food_app.menu_item mi on ci.menu_item_id = mi.id
                where c.customer_id = ?
                and c.restaurant_id = ?
                and c.status = 'ACTIVE'
                order by ci.created_at, ci.id
                """;

        List<CartRow> rows = jdbcTemplate.query(sql, cartRowMapper, customerId, restaurantId);

        if (rows.isEmpty()) {
            log.warn(
                    "Активная корзина не найдена: customerId={}, restaurantId={}",
                    customerId,
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Активная корзина не найдена для customerId=" + customerId + " и restaurantId=" + restaurantId
            );
        }

        CartResponse response = buildCartResponse(rows);

        log.info(
                "Активная корзина загружена: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    //метод добавляет товар в корзину
    @Transactional
    public CartResponse addItemToCart(Long customerId, Long restaurantId, AddCartItemRequest request) {
        log.info(
                "Начинаем добавление позиции в корзину: customerId={}, restaurantId={}, menuItemId={}, quantity={}",
                customerId,
                restaurantId,
                request.menuItemId(),
                request.quantity()
        );

        validateRestaurantIsActive(restaurantId);

        MenuItemSelection menuItem = findMenuItemForCart(restaurantId, request.menuItemId());

        Long cartId = findActiveCartId(customerId, restaurantId);

        if (cartId == null) {
           cartId = createActiveCart(customerId, restaurantId);

            log.info(
                    "Создана новая активная корзина: cartId={}, customerId={}, restaurantId={}",
                    cartId,
                    customerId,
                    restaurantId
            );
        } else {
            log.info("Используем существующую активную корзину: cartId={}", cartId);
        }

        addOrIncreaseCartItem(cartId, menuItem.id(), request.quantity(), menuItem.price());

        touchCart(cartId);

        CartResponse response = getActiveCart(customerId, restaurantId);

        log.info(
                "Позиция успешно добавлена в корзину: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    @Transactional
    public CartResponse updateCartItemQuantity(
            Long customerId,
            Long restaurantId,
            Long cartItemId,
            UpdateCartItemQuantityRequest request
    ) {
        log.info(
                "Начинаем изменение количества позиции корзины: customerId={}, restaurantId={}, cartItemId={}, quantity={}",
                customerId,
                restaurantId,
                cartItemId,
                request.quantity()
        );

        validateRestaurantIsActive(restaurantId);

        Long cartId = findActiveCartId(customerId, restaurantId);

        if (cartId == null) {
            log.warn(
                    "Активная корзина не найдена при изменении количества позиции: customerId={}, restaurantId={}",
                    customerId,
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Активная корзина не найдена для customerId=" + customerId + " и restaurantId=" + restaurantId
            );
        }

            updateCartItemQuantityById(cartId, cartItemId, request.quantity());

            touchCart(cartId);

            CartResponse response = getActiveCart(customerId, restaurantId);

            log.info(
                    "Количество позиции корзины изменено: cartId={}, cartItemId={}, itemsCount={}, totalAmount={}",
                    response.id(),
                    cartItemId,
                    response.items().size(),
                    response.totalAmount()
            );

            return response;
    }

    @Transactional
    public CartResponse removeCartItem(Long customerId, Long restaurantId, Long cartItemId) {
        log.info(
                "Начинаем удаление позиции из корзины: customerId={}, restaurantId={}, cartItemId={}",
                customerId,
                restaurantId,
                cartItemId
        );

        validateRestaurantIsActive(restaurantId);

        Long cartId = findActiveCartId(customerId, restaurantId);

        if (cartId == null) {
            log.warn(
                    "Активная корзина не найдена при удалении позиции: customerId={}, restaurantId={}",
                    customerId,
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Активная корзина не найдена для customerId=" + customerId + " и restaurantId=" + restaurantId
            );
        }

        deleteCartItemById(cartId, cartItemId);

        touchCart(cartId);

        CartResponse response = getActiveCart(customerId, restaurantId);

        log.info(
                "Позиция удалена из корзины: cartId={}, cartItemId={}, itemsCount={}, totalAmount={}",
                response.id(),
                cartItemId,
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    private void updateCartItemQuantityById(
            Long cartId,
            Long cartItemId,
            Integer quantity
    ) {
        String sql = """
                UPDATE food_app.cart_item ci
                SET quantity = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ci.id = ?
                and ci.cart_id = ?
                """;

        int updateRows = jdbcTemplate.update(sql, quantity,  cartItemId, cartId);

        if (updateRows == 0) {
            log.warn(
                    "Позиция корзины не найдена в активной корзине: cartId={}, cartItemId={}",
                    cartId,
                    cartItemId
            );

            throw new ResourceNotFoundException(
                    "Позиция корзины с id=" + cartItemId + " не найдена в активной корзине с id=" + cartId
            );
        }
    }

    private void touchCart(Long cartId) {
        String sql = """
                UPDATE food_app.cart
                SET updated_at = CURRENT_TIMESTAMP
                Where id = ?
                """;

        int updatedRows = jdbcTemplate.update(sql, cartId);

        if (updatedRows == 0) {
            throw new IllegalStateException(
                    "Не удалось обновить время изменения корзины: cartId=" + cartId
            );
        }
    }

    //добавить товар в корзину
    private void addOrIncreaseCartItem(Long cartId,
                                     Long menuItemId,
                                     Integer quantity,
                                     BigDecimal price) {
        String sql = """
                INSERT INTO food_app.cart_item (
                                                  cart_id,
                                                  menu_item_id,
                                                  quantity,
                                                  price
                )
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (cart_id, menu_item_id)
                    DO UPDATE SET
                    quantity = food_app.cart_item.quantity + EXCLUDED.quantity,
                    updated_at = CURRENT_TIMESTAMP
                """;

        int updatedRows = jdbcTemplate.update(sql, cartId, menuItemId, quantity, price);

        if (updatedRows == 0) {
            throw new IllegalStateException(
                    "Не удалось добавить позицию меню в корзину: cartId=" + cartId + ", menuItemId=" + menuItemId
            );
        }
    }

    //создаем новую корзину если до этого не было
    private Long createActiveCart(Long customerId, Long restaurantId) {
        String sql = """
                INSERT INTO food_app.cart (
                                             customer_id,
                                             restaurant_id,
                                             status
                )
                VALUES (?, ?, 'ACTIVE')
                Returning id
                """;
        Long cartId = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                customerId,
                restaurantId
        );

        if (cartId == null) {
            throw new IllegalStateException(
                    "База данных не вернула id созданной корзины для customerId="
                            + customerId + " и restaurantId=" + restaurantId
            );
        }

        return cartId;
    }

    //поиск активной корзины
    private Long findActiveCartId(Long customerId, Long restaurantId) {

        String sql = """
                SELECT
                    c.id
                FROM food_app.cart c
                WHERE c.customer_id = ?
                AND c.restaurant_id = ?
                AND c.status = 'ACTIVE'
        """;

        List<Long> cartIds = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getLong("id"),
                customerId,
                restaurantId
        );

        return cartIds.isEmpty() ? null : cartIds.get(0);
    }

    //проверка блюда перед добавлением в корзину
    private MenuItemSelection findMenuItemForCart(Long restaurantId, Long menuItemId) {
        String sql = """
                SELECT
                    mi.id,
                    mi.price,
                    mi.is_available
                FROM food_app.menu_item mi
                join food_app.menu_category mc on mc.id = mi.category_id
                where mc.restaurant_id = ?
                and mi.id = ?
        """;

        List<MenuItemSelectionRow> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> new MenuItemSelectionRow(
                        rs.getLong("id"),
                        rs.getBigDecimal("price"),
                        rs.getBoolean("is_available")
                ),
                restaurantId,
                menuItemId
        );

        if (rows.isEmpty()) {
            log.warn(
                    "Позиция меню не найдена в ресторане: restaurantId={}, menuItemId={}",
                    restaurantId,
                    menuItemId
            );

            throw new ResourceNotFoundException(
                    "Позиция меню с id=" + menuItemId + " не найдена в ресторане с id=" + restaurantId
            );
        }

        MenuItemSelectionRow row = rows.get(0);

        if (!Boolean.TRUE.equals(row.isAvailable())) {
            log.warn(
                    "Позиция меню недоступна для заказа: restaurantId={}, menuItemId={}",
                    restaurantId,
                    menuItemId
            );

            throw new BusinessRuleViolationException(
                    "Позиция меню с id=" + menuItemId + " сейчас недоступна для заказа"
            );
        }

        return new MenuItemSelection(row.id(), row.price());
    }

    private void deleteCartItemById(Long cartId, Long cartItemId) {

        String sql = """
                DELETE FROM food_app.cart_item ci
                where ci.id = ?
                and ci.cart_id = ?
                """;

        int updateRows = jdbcTemplate.update(sql, cartItemId, cartId);

        if (updateRows == 0) {
            log.warn(
                    "Позиция корзины не найдена при удалении: cartId={}, cartItemId={}",
                    cartId,
                    cartItemId
            );

            throw new ResourceNotFoundException(
                    "Позиция корзины с id=" + cartItemId + " не найдена в активной корзине с id=" + cartId
            );
        }
    }

    //проверка ресторана
    private void validateRestaurantIsActive(Long restaurantId) {

        String sql = """
                select r.is_active
                from food_app.restaurant r
                where r.id = ?
                """;

        List<Boolean> states = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getBoolean("is_active"),
                restaurantId
        );

        if (states.isEmpty()) {
            log.warn(
                    "Ресторан не найден: restaurantId={}",
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Ресторан с id=" + restaurantId + " не найден"
            );
        }

        Boolean isActive = states.get(0);

        if (!Boolean.TRUE.equals(isActive)) {
            log.warn(
                    "Ресторан недоступен для заказов: restaurantId={}",
                    restaurantId
            );


            throw new BusinessRuleViolationException(
                    "Ресторан с id=" + restaurantId + " сейчас недоступен для заказов"
            );
        }


    }

    private CartResponse buildCartResponse(List<CartRow> rows) {
       CartRow firstRow = rows.get(0);

        List<CartItemResponse> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (CartRow row : rows) {
            if (row.cartItemId() != null) {
                BigDecimal lineTotal = row.price().multiply(BigDecimal.valueOf(row.quantity()));

                items.add(
                        new CartItemResponse(
                        row.cartItemId(),
                        row.menuItemId(),
                        row.menuItemName(),
                        row.quantity(),
                        row.price(),
                        lineTotal
                    )
                );
                totalAmount = totalAmount.add(lineTotal);
            }
        }
        return new CartResponse(
                firstRow.cartId(),
                firstRow.customerId(),
                firstRow.restaurantId(),
                firstRow.cartStatus(),
                items,
                totalAmount
        );
    }

    private record MenuItemSelection(
            Long id,
            BigDecimal price
    ) {
    }

    private record MenuItemSelectionRow(
            Long id,
            BigDecimal price,
            Boolean isAvailable
    ) {
    }
}
