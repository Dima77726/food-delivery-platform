package com.dima.fooddelivery.payment.api;

import com.dima.fooddelivery.payment.domain.Payment;

import java.util.List;

public final class PaymentResponseMapper {

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.customerId(),
                payment.amount(),
                payment.status(),
                payment.method(),
                payment.failureReason(),
                payment.createdAt()
        );
    }

    public static List<PaymentResponse> toResponses(List<Payment> payments) {
        return payments.stream().map(PaymentResponseMapper::toResponse).toList();
    }

    private PaymentResponseMapper() {
    }
}
