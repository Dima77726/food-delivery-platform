package com.dima.fooddelivery.tracking.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.delivery.service.DeliveryService;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import com.dima.fooddelivery.tracking.domain.CourierTrack;
import com.dima.fooddelivery.tracking.persistence.CourierTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;


/**
 * Модуль Tracking. Трек курьера по доставке.
 *
 * <p>Права проверяются по PostgreSQL, точки пишутся в Cassandra. Ни одного обращения
 * к чужим таблицам напрямую: доставка спрашивается у {@link DeliveryService}, заказ —
 * у {@link OrderStatusService}.
 *
 * <p><b>Почему проверка прав стоит перед каждой записью, хотя запись дешёвая.</b> Записи
 * в Cassandra не откатываются, и «сначала запишем, потом разберёмся» здесь означало бы, что
 * посторонний может засорять чужой трек. Проверка стоит одного индексного чтения в PostgreSQL
 * и окупается тем, что в партиции доставки лежат только точки её курьера.
 *
 * <p>{@code @Transactional} здесь нет по той же причине, что и в модуле отзывов: менеджер
 * транзакций в приложении управляет PostgreSQL и на Cassandra не распространяется. Читающие
 * проверки открывают собственные короткие транзакции внутри вызываемых сервисов, и этого
 * достаточно.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.CASSANDRA, havingValue = "true")
public class TrackingService {

    private final CourierTrackRepository courierTrackRepository;
    private final DeliveryService deliveryService;
    private final OrderStatusService orderStatusService;

    /**
     * Записывает точку курьера.
     *
     * @throws AccessDeniedForResourceException если доставка назначена другому курьеру
     * @throws BusinessRuleViolationException   если заказ ещё не забран или уже доставлен
     */
    public CourierPosition reportPosition(
            Long courierId,
            Long deliveryId,
            double latitude,
            double longitude,
            Double speedKmh
    ) {
        DeliveryResponse delivery = deliveryService.getDeliveryForCourier(courierId, deliveryId);

        // Трек имеет смысл только пока заказ едет. До того курьер ещё не забрал его,
        // после — везти уже нечего, и точки были бы слежкой за человеком без причины.
        if (!DeliveryStatus.PICKED_UP.name().equals(delivery.status())) {
            throw new BusinessRuleViolationException(
                    "Трек пишется только для доставки в статусе PICKED_UP. Текущий статус="
                            + delivery.status()
            );
        }

        // Время ставит сервер, а не телефон курьера, и генерирует его репозиторий вместе
        // с ключом строки. Часы на устройстве врут, а метка времени здесь ещё и ключ
        // кластеризации: сдвинутые часы переставили бы точки местами внутри партиции.
        CourierPosition position = courierTrackRepository.save(
                deliveryId,
                courierId,
                latitude,
                longitude,
                speedKmh
        );

        log.debug("Точка трека записана: deliveryId={}, courierId={}", deliveryId, courierId);

        return position;
    }

    /** Трек глазами курьера: своя доставка. */
    public CourierTrack getTrackForCourier(Long courierId, Long deliveryId, int limit) {
        deliveryService.getDeliveryForCourier(courierId, deliveryId);

        return new CourierTrack(deliveryId, courierTrackRepository.findLastPositions(deliveryId, limit));
    }

    /**
     * Трек глазами клиента: свой заказ.
     *
     * <p>Клиент не знает идентификатора доставки и знать его не должен — он оперирует заказом.
     * Превращение orderId в deliveryId делает модуль Delivery, ему эта таблица и принадлежит.
     */
    public CourierTrack getTrackForCustomerOrder(Long customerId, Long orderId, int limit) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.belongsToCustomer(customerId)) {
            log.warn("Попытка посмотреть трек чужого заказа: orderId={}, customerId={}", orderId, customerId);

            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        DeliveryResponse delivery = deliveryService.getDeliveryForOrder(orderId);

        return new CourierTrack(delivery.id(), courierTrackRepository.findLastPositions(delivery.id(), limit));
    }
}
