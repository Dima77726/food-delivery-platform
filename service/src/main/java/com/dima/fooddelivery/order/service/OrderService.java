package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.cart.domain.Cart;
import com.dima.fooddelivery.cart.domain.CartItem;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.order.api.OrderEventResponse;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.api.OrderResponseMapper;
import com.dima.fooddelivery.order.api.OrderSummaryResponse;
import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderSummary;
import com.dima.fooddelivery.order.persistence.OrderEventRepository;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import com.dima.fooddelivery.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Сценарии работы с заказом со стороны клиента и ресторана.
 *
 * <p>SQL здесь больше нет: запросы живут в {@link OrderRepository}, смена статусов — в
 * {@link OrderStatusService}. Этому классу остались решения: кто вправе, что считать ошибкой
 * и в каком порядке дёргать соседние модули.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final OrderStatusService orderStatusService;
    private final CartService cartService;
    private final PaymentService paymentService;

    /**
     * Создаёт заказ из активной корзины клиента.
     *
     * <p>Порядок шагов важен: корзина закрывается до фиксации транзакции, поэтому два
     * параллельных запроса не смогут создать два заказа из одной корзины — второй упадёт
     * на compare-and-set внутри {@link CartService#checkout(Long)}.
     */
    @Transactional
    public OrderResponse createOrderFromActiveCart(Long customerId, Long restaurantId) {
        Cart cart = cartService.requireActiveCart(customerId, restaurantId);

        if (cart.isEmpty()) {
            log.warn("Попытка создать заказ из пустой корзины: cartId={}", cart.id());

            throw new BusinessRuleViolationException(
                    "Нельзя создать заказ из пустой корзины: cartId=" + cart.id()
            );
        }

        BigDecimal totalAmount = cart.totalAmount();

        Long orderId = orderRepository.insert(
                cart.id(),
                customerId,
                restaurantId,
                OrderStatus.CREATED,
                totalAmount
        );

        orderRepository.insertItems(orderId, cart.items().stream()
                .map(OrderService::toNewOrderItem)
                .toList());

        cartService.checkout(cart.id());

        orderStatusService.recordEvent(
                orderId,
                OrderEventType.ORDER_CREATED,
                "Заказ создан из активной корзины"
        );

        log.info(
                "Заказ создан: orderId={}, customerId={}, itemsCount={}, totalAmount={}",
                orderId,
                customerId,
                cart.items().size(),
                totalAmount
        );

        return OrderResponseMapper.toResponse(requireOrder(orderId));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderByIdForCustomer(Long customerId, Long orderId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> {
                    log.warn("Заказ клиента не найден: customerId={}, orderId={}", customerId, orderId);

                    return new ResourceNotFoundException(
                            "Заказ с id=" + orderId + " не найден для customerId=" + customerId
                    );
                });

        return OrderResponseMapper.toResponse(order);
    }

    /**
     * Оба списка постраничные. Раньше они возвращали всё, что есть: у клиента с сотней заказов
     * это ещё работало, у ресторана с историей за год — уже нет.
     *
     * <p>COUNT выполняется вторым запросом в той же транзакции, поэтому итог согласован
     * с содержимым страницы.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getOrdersByCustomer(Long customerId, PageRequestParams page) {
        List<OrderSummary> summaries =
                orderRepository.findSummariesByCustomerId(customerId, page.size(), page.offset());

        return PageResponse.of(
                OrderResponseMapper.toSummaryResponses(summaries),
                page.page(),
                page.size(),
                orderRepository.countByCustomerId(customerId)
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getOrdersForRestaurant(
            Long restaurantId,
            OrderStatus status,
            PageRequestParams page
    ) {
        List<OrderSummary> summaries =
                orderRepository.findSummariesByRestaurantId(restaurantId, status, page.size(), page.offset());

        return PageResponse.of(
                OrderResponseMapper.toSummaryResponses(summaries),
                page.page(),
                page.size(),
                orderRepository.countByRestaurantId(restaurantId, status)
        );
    }

    @Transactional(readOnly = true)
    public List<OrderEventResponse> getOrderEventsForCustomer(Long customerId, Long orderId) {
        if (!orderRepository.existsForCustomer(orderId, customerId)) {
            log.warn("Заказ не найден при чтении истории: customerId={}, orderId={}", customerId, orderId);

            throw new ResourceNotFoundException(
                    "Заказ с id=" + orderId + " не найден для customerId=" + customerId
            );
        }

        return OrderResponseMapper.toEventResponses(orderEventRepository.findByOrderId(orderId));
    }

    @Transactional
    public OrderResponse cancelOrder(Long customerId, Long orderId) {
        OrderAccess access = requireCustomerOrder(customerId, orderId);

        if (!access.status().canBeCanceledByCustomer()) {
            throw new BusinessRuleViolationException(
                    "Отменить можно только заказ в статусе CREATED или PAID. Текущий статус="
                            + access.status().getDbValue()
            );
        }

        boolean wasPaid = access.status() == OrderStatus.PAID;

        orderStatusService.transition(
                orderId,
                access.status(),
                OrderStatus.CANCELED,
                OrderEventType.ORDER_CANCELED,
                "Заказ отменён клиентом"
        );

        // Возврат после смены статуса, а не до: если переход не состоялся из-за гонки,
        // деньги возвращать не за что. Обе операции в одной транзакции.
        if (wasPaid) {
            paymentService.refundForOrder(orderId);
        }

        return OrderResponseMapper.toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse acceptOrder(Long restaurantId, Long orderId) {
        OrderAccess access = requireRestaurantOrder(restaurantId, orderId);

        if (!access.status().canBeAcceptedByRestaurant()) {
            throw new BusinessRuleViolationException(
                    "Принять можно только оплаченный заказ (статус PAID). Текущий статус="
                            + access.status().getDbValue()
            );
        }

        orderStatusService.transition(
                orderId,
                OrderStatus.PAID,
                OrderStatus.ACCEPTED,
                OrderEventType.ORDER_ACCEPTED,
                "Заказ принят рестораном"
        );

        return OrderResponseMapper.toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse startCookingOrder(Long restaurantId, Long orderId) {
        OrderAccess access = requireRestaurantOrder(restaurantId, orderId);

        if (!access.status().canStartCookingByRestaurant()) {
            throw new BusinessRuleViolationException(
                    "Начать готовку можно только для заказа в статусе ACCEPTED. Текущий статус="
                            + access.status().getDbValue()
            );
        }

        orderStatusService.transition(
                orderId,
                OrderStatus.ACCEPTED,
                OrderStatus.COOKING,
                OrderEventType.ORDER_COOKING_STARTED,
                "Ресторан начал готовить заказ"
        );

        return OrderResponseMapper.toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse markOrderReadyForDelivery(Long restaurantId, Long orderId) {
        OrderAccess access = requireRestaurantOrder(restaurantId, orderId);

        if (!access.status().canBeMarkedReadyForDeliveryByRestaurant()) {
            throw new BusinessRuleViolationException(
                    "Отметить готовым к доставке можно только заказ в статусе COOKING. Текущий статус="
                            + access.status().getDbValue()
            );
        }

        orderStatusService.transition(
                orderId,
                OrderStatus.COOKING,
                OrderStatus.READY_FOR_DELIVERY,
                OrderEventType.ORDER_READY_FOR_DELIVERY,
                "Заказ отмечен рестораном как готовый к доставке"
        );

        return OrderResponseMapper.toResponse(requireOrder(orderId));
    }

    @Transactional(readOnly = true)
    public Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Заказ с id=" + orderId + " не найден"));
    }

    /**
     * Проверка владения вынесена из SQL в сервис намеренно.
     *
     * <p>Раньше условие {@code AND customer_id = ?} стояло прямо в UPDATE, и чужой заказ давал
     * 409 «статус изменился» вместо честного отказа в доступе. Теперь «не твой заказ» и «заказ
     * уже уехал» — разные ошибки с разными кодами, а когда появится проверка прав на уровне
     * Spring Security, переносить придётся только эти два метода.
     */
    private OrderAccess requireCustomerOrder(Long customerId, Long orderId) {
        OrderAccess access = orderStatusService.requireAccess(orderId);

        if (!access.belongsToCustomer(customerId)) {
            log.warn(
                    "Попытка обратиться к чужому заказу: orderId={}, customerId={}",
                    orderId,
                    customerId
            );

            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        return access;
    }

    private OrderAccess requireRestaurantOrder(Long restaurantId, Long orderId) {
        OrderAccess access = orderStatusService.requireAccess(orderId);

        if (!access.belongsToRestaurant(restaurantId)) {
            log.warn(
                    "Попытка обратиться к заказу чужого ресторана: orderId={}, restaurantId={}",
                    orderId,
                    restaurantId
            );

            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " оформлен в другом ресторане");
        }

        return access;
    }

    private static OrderRepository.NewOrderItem toNewOrderItem(CartItem item) {
        return new OrderRepository.NewOrderItem(
                item.menuItemId(),
                item.menuItemName(),
                item.quantity(),
                item.price(),
                item.lineTotal()
        );
    }
}
