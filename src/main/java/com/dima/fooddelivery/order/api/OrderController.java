package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/v1/customers/{customerId}/restaurants/{restaurantId}/orders")
    public OrderResponse createOrder(
            @PathVariable Long customerId,
            @PathVariable Long restaurantId
    ) {
        log.info(
                "Received request to create order from active cart: customerId={}, restaurantId={}",
                customerId,
                restaurantId
        );

        OrderResponse response = orderService.createOrderFromActiveCart(customerId, restaurantId);

        log.info(
                "Order created successfully: orderId={}, cartId={}, customerId={}, restaurantId={}, totalAmount={}",
                response.id(),
                response.cartId(),
                response.customerId(),
                response.restaurantId(),
                response.totalAmount()
        );
        return response;
    }
}
