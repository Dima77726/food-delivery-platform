package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.CartItemResponse;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.persistence.CartRow;
import com.dima.fooddelivery.cart.persistence.CartRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CartService {

    private final JdbcTemplate jdbcTemplate;
    private final CartRowMapper cartRowMapper;

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
            throw new RuntimeException("Active cart not found for customerId=" + customerId + " and restaurantId=" + restaurantId);
        }

        CartResponse response = buildCartResponse(rows);

        log.info( "Active cart loaded: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount());

        return response;
    }

    private CartResponse buildCartResponse(List<CartRow> rows) {
       CartRow firsRow = rows.get(0);

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
                firsRow.cartId(),
                firsRow.customerId(),
                firsRow.restaurantId(),
                firsRow.cartStatus(),
                items,
                totalAmount
        );
    }
}
