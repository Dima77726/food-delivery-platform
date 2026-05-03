package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}")
    public OrderResponse getOrderById(
            @PathVariable Long customerId,
            @PathVariable Long orderId
    ) {
        log.info(
                "Received request to fetch order: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderResponse response = orderService.getOrderByIdForCustomer(customerId, orderId);

        log.info(
                "Returning order: orderId={}, customerId={}, status={}, totalAmount={}, itemsCount={}",
                response.id(),
                response.customerId(),
                response.status(),
                response.totalAmount(),
                response.items().size()
        );

        return response;
    }

    @GetMapping("/api/v1/customers/{customerId}/orders")
    public List<OrderSummaryResponse> getOrdersByCustomer(
            @PathVariable Long customerId
    ) {
        log.info(
                "Received request to fetch customer orders: customerId={}",
                customerId
        );

        List<OrderSummaryResponse> response = orderService.getOrdersByCustomer(customerId);

        log.info(
                "Returning customer orders: customerId={}, ordersCount={}",
                customerId,
                response.size()
        );

        return response;
    }

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(
            @PathVariable Long customerId,
            @PathVariable Long orderId
    ) {
        log.info(
                "Received request to cancel order: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderResponse response = orderService.cancelOrder(customerId, orderId);

        log.info(
                "Order canceled successfully: orderId={}, customerId={}, status={}",
                response.id(),
                response.customerId(),
                response.status()
        );

        return response;
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}/events")
    public List<OrderEventResponse> getOrderEvents(
            @PathVariable Long customerId,
            @PathVariable Long orderId
    ) {
        log.info(
                "Received request to fetch order events: customerId={}, orderId={}",
                customerId,
                orderId
        );

        List<OrderEventResponse> response = orderService.getOrderEventsForCustomer(customerId, orderId);

        log.info(
                "Returning order events: customerId={}, orderId={}, eventsCount={}",
                customerId,
                orderId,
                response.size()
        );

        return response;
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/accept")

    public OrderResponse acceptOrder(
            @PathVariable Long restaurantId,
            @PathVariable Long orderId
    ) {
        log.info(
            "Received request to accept order: restaurantId={}, orderId={}",
            restaurantId,
            orderId
        );

        OrderResponse response = orderService.acceptOrder(restaurantId, orderId);

        log.info(
                "Order accepted successfully: restaurantId={}, orderId={}, status={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }
}
