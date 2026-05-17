package com.dima.fooddelivery.delivery.api;

import com.dima.fooddelivery.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @PostMapping("/api/v1/orders/{orderId}/deliveries")
    public DeliveryResponse createDelivery(@PathVariable Long orderId) {
        log.info(
                "Получен запрос на создание доставки для заказа: orderId ={}",
                orderId
        );

        DeliveryResponse response = deliveryService.createDeliveryForOrder(orderId);

        log.info(
                "Доставка успешно создана: deliveryId={}, orderId={}, status={}",
                response.id(),
                response.orderId(),
                response.status()
        );

        return response;
    }
}
