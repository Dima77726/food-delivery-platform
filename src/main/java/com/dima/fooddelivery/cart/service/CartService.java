package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartItemResponse;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.persistence.CartRow;
import com.dima.fooddelivery.cart.persistence.CartRowMapper;
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
    public CartResponse getActiveCart(Long customerId, Long restaurantId) {
        log.info("Fetching active cart for customerId={}, restaurantId={}",  customerId, restaurantId);

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
            log.warn("Active cart not found for customerId={}, restaurantId={}", customerId, restaurantId);
            throw new ResourceNotFoundException(
                    "Active cart not found for customerId=" + customerId + " and restaurantId=" + restaurantId
            );
        }

        CartResponse response = buildCartResponse(rows);

        log.info( "Active cart loaded: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount());

        return response;
    }

    //метод добавляет товар в корзину
    @Transactional
    public CartResponse addItemToCart(Long customerId, Long restaurantId, AddCartItemRequest request) {
        log.info("Adding item to cart: customerId={}, restaurantId={}, menuItemId={}, quantity={}",
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
           log.info("Created new active cart: cartId={}, customerId={}, restaurantId={}",
                   cartId,
                   customerId,
                   restaurantId
           );
        } else {
            log.info("Using existing active cart: cartId={}", cartId);
        }

        addOrIncreaseCartItem(cartId, menuItem.id(), request.quantity(), menuItem.price());

        touchCart(cartId);

        CartResponse response = getActiveCart(customerId, restaurantId);

        log.info("Item added to cart successfully: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    private void touchCart(Long cartId) {
        String sql = """
                UPDATE food_app.cart
                SET updated_at = CURRENT_TIMESTAMP
                Where id = ?
                """;

        int updatedRows = jdbcTemplate.update(sql, cartId);

        if (updatedRows == 0) {
            throw new IllegalStateException("Failed to update cart timestamp for cartId=" + cartId);
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
            throw new IllegalStateException("Failed to add menu item to cart: cartId=" + cartId + ", menuItemId=" + menuItemId);
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
            throw new IllegalStateException("Failed to create active cart for customerId=" + customerId + " and restaurantId=" + restaurantId);
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
            log.warn("Menu item not found in restaursant: restaurantId={}, menuItemId={}",
                    restaurantId,
                    menuItemId
            );
            throw new ResourceNotFoundException("Menu item with id=" + menuItemId + " not found in restaurant with id=" + restaurantId);
        }

        MenuItemSelectionRow row = rows.get(0);

        if (!Boolean.TRUE.equals(row.isAvailable())) {
            log.warn("Menu item is unavailable: restaurantId={}, menuItemId={}",
                    restaurantId,
                    menuItemId
            );
            throw new ResourceNotFoundException("Menu item with id=" + menuItemId + " is unavailable");
        }

        return new MenuItemSelection(row.id(), row.price());
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
            log.warn("Restaurant not found for restaurantId={}", restaurantId);
            throw new ResourceNotFoundException("Restaurant not found for restaurantId=" + restaurantId);
        }

        Boolean isActive = states.get(0);
        
        if (!Boolean.TRUE.equals(isActive)) {
            log.warn("Restaurant is not active for restaurantId={}", restaurantId);
            throw new IllegalStateException("Restaurant is not active for restaurantId=" + restaurantId);
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
