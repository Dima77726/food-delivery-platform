package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import com.dima.fooddelivery.order.persistence.OrderEventRepository;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Единственная точка, через которую меняется статус заказа.
 *
 * <p>Это публичный API модуля Order для соседних модулей. Раньше Delivery писал в
 * {@code customer_order} и {@code customer_order_event} своими запросами — из-за этого метод
 * записи события существовал в двух копиях, а правило перехода можно было нарушить в обход
 * модуля-владельца. Правило теперь записано так:
 *
 * <blockquote>в таблицы модуля пишет только его собственный репозиторий; другие модули
 * ходят через сервисы.</blockquote>
 *
 * <p>Транзакцию этот сервис не открывает заново, а присоединяется к вызывающей
 * ({@code REQUIRED} по умолчанию). Поэтому смена статуса доставки и смена статуса заказа
 * в {@code DeliveryService} остаются атомарными: либо оба изменения, либо ни одного.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Владелец и статус заказа.
     *
     * @throws ResourceNotFoundException если заказа нет
     */
    @Transactional(readOnly = true)
    public OrderAccess requireAccess(Long orderId) {
        return orderRepository.findAccess(orderId)
                .orElseThrow(() -> {
                    log.warn("Заказ не найден: orderId={}", orderId);
                    return new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
                });
    }

    /**
     * Переводит заказ из {@code expected} в {@code next} и дописывает событие в историю.
     *
     * <p>Проверка статуса живёт внутри UPDATE, а не перед ним: между чтением и записью заказ
     * может увести параллельный запрос. Ноль обновлённых строк — это гонка, и она честно
     * превращается в 409, а не в тихо потерянное изменение.
     *
     * @throws BusinessRuleViolationException если статус успел измениться
     */
    @Transactional
    public void transition(
            Long orderId,
            OrderStatus expected,
            OrderStatus next,
            OrderEventType eventType,
            String description
    ) {
        int updated = orderRepository.compareAndSetStatus(orderId, expected, next);

        if (updated == 0) {
            log.warn(
                    "Переход заказа не выполнен, статус изменился: orderId={}, expected={}, next={}",
                    orderId,
                    expected.getDbValue(),
                    next.getDbValue()
            );

            throw new BusinessRuleViolationException(
                    "Не удалось перевести заказ с id=" + orderId + " из статуса " + expected.getDbValue()
                            + " в " + next.getDbValue() + ". Возможно, заказ уже изменил статус"
            );
        }

        recordEvent(orderId, eventType, description);

        // Публикация в той же транзакции: слушатели пишут в нашу же базу, и их записи
        // должны откатиться вместе со сменой статуса, если дальше что-то упадёт.
        OrderAccess access = requireAccess(orderId);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                orderId,
                access.customerId(),
                access.restaurantId(),
                expected,
                next,
                eventType,
                access.totalAmount()
        ));

        log.info(
                "Заказ сменил статус: orderId={}, {} -> {}",
                orderId,
                expected.getDbValue(),
                next.getDbValue()
        );
    }

    /**
     * Дописывает событие без смены статуса — например, о неудачной попытке оплаты.
     */
    @Transactional
    public void recordEvent(Long orderId, OrderEventType eventType, String description) {
        int inserted = orderEventRepository.append(orderId, eventType, description);

        if (inserted != 1) {
            // Не бизнес-правило, а сломанный инвариант: INSERT без исключения обязан вставить строку.
            throw new IllegalStateException(
                    "Не удалось записать событие заказа: orderId=" + orderId
                            + ", eventType=" + eventType.getDbValue()
            );
        }
    }

    // --- Именованные переходы для соседних модулей -------------------------------------------

    /** Вызывается модулем Payment после успешной оплаты. */
    @Transactional
    public void markPaid(Long orderId) {
        transition(orderId, OrderStatus.CREATED, OrderStatus.PAID, OrderEventType.ORDER_PAID,
                "Заказ оплачен клиентом");
    }

    /** Вызывается модулем Delivery, когда курьер забрал заказ из ресторана. */
    @Transactional
    public void markInDelivery(Long orderId) {
        transition(orderId, OrderStatus.READY_FOR_DELIVERY, OrderStatus.IN_DELIVERY,
                OrderEventType.ORDER_PICKED_UP_BY_COURIER, "Заказ забран курьером из ресторана");
    }

    /** Вызывается модулем Delivery при завершении доставки. */
    @Transactional
    public void markDelivered(Long orderId) {
        transition(orderId, OrderStatus.IN_DELIVERY, OrderStatus.DELIVERED,
                OrderEventType.ORDER_DELIVERED, "Заказ доставлен клиенту");
    }
}
