package com.dima.fooddelivery.payment.api;

import com.dima.fooddelivery.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/customers/{customerId}")
@Tag(name = "Payment", description = "Оплата заказов")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/orders/{orderId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Оплатить заказ; повтор с тем же ключом идемпотентности безопасен")
    public PaymentResponse payForOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId,

            @Valid @RequestBody PayOrderRequest request
    ) {
        log.info("Оплата заказа: customerId={}, orderId={}, method={}", customerId, orderId, request.method());

        return paymentService.payForOrder(customerId, orderId, request);
    }

    @GetMapping("/orders/{orderId}/payments")
    @Operation(summary = "Платежи по заказу, включая неудачные попытки")
    public List<PaymentResponse> getPaymentsForOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        return paymentService.getPaymentsForOrder(customerId, orderId);
    }

    @GetMapping("/payments")
    @Operation(summary = "Все платежи клиента")
    public List<PaymentResponse> getPaymentsForCustomer(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId
    ) {
        return paymentService.getPaymentsForCustomer(customerId);
    }
}
