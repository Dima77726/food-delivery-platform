package com.dima.fooddelivery.recommendation.service;

import com.dima.fooddelivery.common.stores.Neo4jStoreProperties;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import com.dima.fooddelivery.recommendation.persistence.RecommendationGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Модуль Recommendation. Граф заказов и рекомендации по нему.
 *
 * <p><b>Как данные попадают в граф.</b> Не событиями из Kafka, в отличие от аналитики,
 * и это не непоследовательность. Событию заказа состав не нужен — его читают уведомления
 * и витрина продаж, которым хватает суммы, — а графу нужен именно состав. Добавлять позиции
 * в событие означало бы платить лишним запросом при каждой смене статуса ради потребителя,
 * которого может не быть вовсе.
 *
 * <p>Поэтому здесь другой, тоже вполне обычный способ: пакетное чтение из системы записи
 * с меткой прочитанного. Отставание графа от PostgreSQL измеряется интервалом проекции,
 * и для рекомендаций это совершенно неважно — они и так строятся на истории за месяцы.
 *
 * <p><b>Отменённые заказы в граф не попадают.</b> Заказ, который клиент отменил, не говорит
 * о его вкусах ничего - скорее наоборот. Витрина продаж их при этом учитывает: там вопрос
 * не о вкусе, а о деньгах и о том, где заказы теряются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.NEO4J, havingValue = "true")
public class RecommendationService {

    /**
     * Предохранитель для полного перестроения: не больше стольких проходов за один вызов.
     * При пачке в двести заказов это сорок тысяч заказов — достаточно для учебной базы
     * и достаточно мало, чтобы случайный вызов не занял сервер на час.
     */
    private static final int MAX_REBUILD_BATCHES = 200;

    private final RecommendationGraph recommendationGraph;
    private final OrderService orderService;
    private final Neo4jStoreProperties properties;

    public List<RecommendedItem> recommendForCustomer(Long customerId, int limit) {
        return recommendationGraph.recommendForCustomer(customerId, limit);
    }

    public List<RecommendedItem> orderedTogetherWith(Long menuItemId, int limit) {
        return recommendationGraph.orderedTogetherWith(menuItemId, limit);
    }

    /**
     * Догоняет граф на одну пачку заказов.
     *
     * @return сколько заказов прочитано из PostgreSQL; ноль означает, что граф в актуальном
     * состоянии
     */
    public int projectNextBatch() {
        long lastProjected = recommendationGraph.lastProjectedOrderId();

        List<Order> batch = orderService.findForProjection(lastProjected, properties.projectionBatch());

        if (batch.isEmpty()) {
            return 0;
        }

        // Метка двигается по последнему прочитанному заказу, а не по последнему
        // спроецированному: отфильтрованные отменённые заказы тоже прочитаны,
        // и возвращаться к ним незачем.
        long lastRead = batch.get(batch.size() - 1).id();

        List<Order> projectable = batch.stream()
                .filter(order -> order.status() != OrderStatus.CANCELED)
                .filter(order -> !order.items().isEmpty())
                .toList();

        recommendationGraph.projectOrders(projectable, lastRead);

        return batch.size();
    }

    /**
     * Перестраивает граф с нуля.
     *
     * <p>Операция для администратора и для случая, когда модель графа поменялась: старые узлы
     * и связи построены по прежним правилам, и догонять их проекцией бессмысленно. Возможность
     * выбросить всё и построить заново — главное практическое следствие того, что граф
     * производный.
     *
     * @return сколько заказов прочитано
     */
    public int rebuild() {
        recommendationGraph.clear();

        int total = 0;

        for (int pass = 0; pass < MAX_REBUILD_BATCHES; pass++) {
            int read = projectNextBatch();

            if (read == 0) {
                break;
            }

            total += read;
        }

        log.info("Граф рекомендаций перестроен, прочитано заказов: {}", total);

        return total;
    }
}
