package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.payment.api.PayOrderRequest;
import com.dima.fooddelivery.payment.api.PaymentResponse;
import com.dima.fooddelivery.payment.api.PaymentResponseMapper;
import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import com.dima.fooddelivery.payment.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Модуль Payment.
 *
 * <p>Настоящего платёжного шлюза здесь нет — списание денег имитируется
 * {@link PaymentGateway}. Важно другое: обвязка вокруг шлюза написана так, как она должна
 * выглядеть в бою — идемпотентность, отдельные записи на каждую попытку, честный статус FAILED
 * с причиной. Когда шлюз появится, меняется одна реализация интерфейса.
 *
 * <p>Статус заказа этот модуль сам не трогает: зовёт {@link OrderStatusService#markPaid(Long)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderStatusService orderStatusService;
    private final PaymentGateway paymentGateway;

    /**
     * Оплачивает заказ.
     *
     * <p>Повторный вызов с тем же {@code idempotencyKey} не создаёт второй платёж, а возвращает
     * результат первого. Это защита от двойного клика и от ретраев на стороне клиента: оба
     * случая выглядят для сервера как два одинаковых HTTP-запроса.
     */
    @Transactional
    public PaymentResponse payForOrder(Long customerId, Long orderId, PayOrderRequest request) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.belongsToCustomer(customerId)) {
            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        // Проверка ключа идёт до проверки статуса: повтор оплаты уже оплаченного заказа
        // с тем же ключом — это не ошибка, а тот же самый запрос.
        var existing = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Payment payment = existing.get();

            if (!payment.orderId().equals(orderId)) {
                throw new BusinessRuleViolationException(
                        "Ключ идемпотентности уже использован для другого заказа"
                );
            }

            log.info("Повторный запрос оплаты по тому же ключу: paymentId={}", payment.id());

            return PaymentResponseMapper.toResponse(payment);
        }

        if (!order.status().canBePaidByCustomer()) {
            throw new BusinessRuleViolationException(
                    "Оплатить можно только заказ в статусе CREATED. Текущий статус="
                            + order.status().getDbValue()
            );
        }

        // Сумма берётся из заказа, а не из запроса: иначе клиент присылал бы её сам
        // и платил столько, сколько захочет.
        BigDecimal amount = order.totalAmount();

        Long paymentId;
        try {
            paymentId = paymentRepository.insert(
                    orderId,
                    customerId,
                    amount,
                    PaymentStatus.PENDING,
                    request.method(),
                    request.idempotencyKey()
            );
        } catch (DuplicateKeyException exception) {
            // Гонка двух одинаковых запросов: первый успел вставить строку между нашей
            // проверкой ключа и вставкой. Отдаём результат победителя.
            return paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                    .map(PaymentResponseMapper::toResponse)
                    .orElseThrow(() -> exception);
        }

        PaymentGateway.ChargeResult result = paymentGateway.charge(amount, request.method(), orderId);

        if (!result.successful()) {
            paymentRepository.compareAndSetStatus(
                    paymentId,
                    PaymentStatus.PENDING,
                    PaymentStatus.FAILED,
                    result.failureReason()
            );

            orderStatusService.recordEvent(
                    orderId,
                    OrderEventType.ORDER_PAYMENT_FAILED,
                    "Оплата не прошла: " + result.failureReason()
            );

            log.warn("Оплата не прошла: orderId={}, reason={}", orderId, result.failureReason());

            // 409, а не 500: отказ банка — штатный исход, а не сбой приложения.
            throw new BusinessRuleViolationException("Оплата не прошла: " + result.failureReason());
        }

        paymentRepository.compareAndSetStatus(paymentId, PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, null);

        orderStatusService.markPaid(orderId);

        log.info("Заказ оплачен: orderId={}, paymentId={}, amount={}", orderId, paymentId, amount);

        return PaymentResponseMapper.toResponse(requirePayment(paymentId));
    }

    /**
     * Возврат средств. Вызывается при отмене оплаченного заказа.
     *
     * <p>Молча ничего не делает, если успешного платежа нет: отмена неоплаченного заказа —
     * нормальный сценарий, и падать на нём нельзя.
     */
    @Transactional
    public void refundForOrder(Long orderId) {
        var succeeded = paymentRepository.findSucceededByOrderId(orderId);

        if (succeeded.isEmpty()) {
            log.debug("Возврат не требуется, успешных платежей нет: orderId={}", orderId);
            return;
        }

        Payment payment = succeeded.get();

        paymentGateway.refund(payment.amount(), orderId);

        int updated = paymentRepository.compareAndSetStatus(
                payment.id(),
                PaymentStatus.SUCCEEDED,
                PaymentStatus.REFUNDED,
                null
        );

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось оформить возврат по платежу с id=" + payment.id()
                            + ". Возможно, возврат уже выполнен"
            );
        }

        orderStatusService.recordEvent(
                orderId,
                OrderEventType.ORDER_REFUNDED,
                "Возврат средств за отменённый заказ: " + payment.amount()
        );

        log.info("Оформлен возврат: orderId={}, paymentId={}, amount={}", orderId, payment.id(), payment.amount());
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForCustomer(Long customerId) {
        return PaymentResponseMapper.toResponses(paymentRepository.findByCustomerId(customerId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForOrder(Long customerId, Long orderId) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.belongsToCustomer(customerId)) {
            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        return PaymentResponseMapper.toResponses(paymentRepository.findByOrderId(orderId));
    }

    private Payment requirePayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Платёж с id=" + paymentId + " не найден"));
    }

    /**
     * Граница с внешним миром. Отдельный интерфейс, а не метод внутри сервиса, чтобы
     * в тестах подменять исход оплаты, не поднимая ничего лишнего.
     */
    public interface PaymentGateway {

        ChargeResult charge(BigDecimal amount, PaymentMethod method, Long orderId);

        void refund(BigDecimal amount, Long orderId);

        record ChargeResult(boolean successful, String failureReason) {

            public static ChargeResult success() {
                return new ChargeResult(true, null);
            }

            public static ChargeResult failure(String reason) {
                return new ChargeResult(false, reason);
            }
        }
    }
}
