package com.dima.fooddelivery.cart.api;

import com.dima.fooddelivery.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    @GetMapping("api/v1/customers/{customerId}/restaurants/{restaurantId}/cart")
    public CartResponse getActiveCart(@PathVariable Long customerId, @PathVariable Long restaurantId) {

        log.info("Received request to fetch active cart for customerId={}, restaurantId={}", customerId, restaurantId);

        CartResponse response = cartService.getActiveCart(customerId, restaurantId);

        log.info( "Returning active cart: cartId={}, itemsCount={}, totalAmount={}", response.id(), response.items().size(), response.totalAmount());

        return response;
    }

    @PostMapping("api/v1/customers/{customerId}/restaurants/{restaurantId}/cart")
    public CartResponse addItemToCart(@PathVariable Long customerId,
                                      @PathVariable Long restaurantId,
                                      @Valid @RequestBody AddCartItemRequest request) {
        log.info("Received request to add item to cart: customerId={}, restaurantId={}, menuItemId={}, quantity={}",
                customerId,
                restaurantId,
                request.menuItemId(),
                request.quantity()
        );

        CartResponse response = cartService.addItemToCart(customerId, restaurantId, request);

        log.info("Item added to cart successfully: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount());

        return response;
    }

    @PatchMapping("api/v1/customers/{customerId}/restaurants/{restaurantId}/cart/items/{cartItemId}")
    public CartResponse updateCartItemQuantity(
            @PathVariable Long customerId,
            @PathVariable Long restaurantId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemQuantityRequest request
    ) {
        log.info( "Received request to update cart item quantity: customerId={}, restaurantId={}, cartItemId={}, quantity={}",
                customerId,
                restaurantId,
                cartItemId,
                request.quantity()
        );

        CartResponse response = cartService.updateCartItemQuantity(
                customerId,
                restaurantId,
                cartItemId,
                request
        );

        log.info(
                "Cart item quantity updated successfully: cartId={}, cartItemId={}, itemsCount={}, totalAmount={}",
                response.id(),
                cartItemId,
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

}
