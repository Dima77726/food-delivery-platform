package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.service.OrderService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Slf4j
@Validated
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/v1/customers/{customerId}/restaurants/{restaurantId}/orders")
    public OrderResponse createOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        log.info(
                "Получен запрос на создание заказа из активной корзины: customerId={}, restaurantId={}",
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
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info(
                "Получен запрос на получение заказа: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderResponse response = orderService.getOrderByIdForCustomer(customerId, orderId);

        log.info(
                "Возвращаем заказ: orderId={}, customerId={}, status={}, totalAmount={}, itemsCount={}",
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
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId
    ) {
        log.info(
                "Получен запрос на получение списка заказов клиента: customerId={}",
                customerId
        );

        List<OrderSummaryResponse> response = orderService.getOrdersByCustomer(customerId);

        log.info(
                "Возвращаем список заказов клиента: customerId={}, ordersCount={}",
                customerId,
                response.size()
        );

        return response;
    }

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/cancel")
    public OrderResponse cancelOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info(
                "Получен запрос на отмену заказа: customerId={}, orderId={}",
                customerId,
                orderId
        );

        OrderResponse response = orderService.cancelOrder(customerId, orderId);

        log.info(
                "Заказ успешно отменён: orderId={}, customerId={}, status={}",
                response.id(),
                response.customerId(),
                response.status()
        );

        return response;
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}/events")
    public List<OrderEventResponse> getOrderEvents(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info(
                "Получен запрос на получение истории событий заказа: customerId={}, orderId={}",
                customerId,
                orderId
        );

        List<OrderEventResponse> response = orderService.getOrderEventsForCustomer(customerId, orderId);

        log.info(
                "Возвращаем историю событий заказа: customerId={}, orderId={}, eventsCount={}",
                customerId,
                orderId,
                response.size()
        );

        return response;
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/accept")

    public OrderResponse acceptOrder(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info(
                "Получен запрос на принятие заказа рестораном: restaurantId={}, orderId={}",
            restaurantId,
            orderId
        );

        OrderResponse response = orderService.acceptOrder(restaurantId, orderId);

        log.info(
                "Заказ успешно принят рестораном: restaurantId={}, orderId={}, status={}",
                restaurantId,
                orderId,
                response.status()
        );

        return response;
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/start-cooking")
    public OrderResponse startCookingOrder(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    )
    {
        log.info(
                "Получен запрос на начало готовки заказа: restaurantId={}, orderId={}",
                restaurantId,
                orderId
                );

        OrderResponse response = orderService.startCookingOrder(restaurantId, orderId);

        log.info(
                "Готовка заказа успешно начата: restaurantId={}, orderId={}, status={}",
                restaurantId,
                response.id(),
                response.status()
        );

        return response;
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/ready-for-delivery")
    public OrderResponse markOrderReadyForDelivery(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info(
                "Получен запрос на отметку заказа готовым к доставке: restaurantId={}, orderId={}",
                restaurantId,
                orderId
        );

        OrderResponse response = orderService.markOrderReadyForDelivery(restaurantId, orderId);

        log.info(
                "Заказ успешно отмечен готовым к доставке: restaurantId={}, orderId={}, status={}",
                restaurantId,
                response.id(),
                response.status()
        );

        return response;
    }
}
