package com.dima.fooddelivery.recommendation.service;

import com.dima.fooddelivery.common.stores.Neo4jStoreProperties;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.domain.Order;
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
 * с меткой прочитанного. После конца выборки начинается новый проход: он учитывает отмены
 * уже прочитанных заказов и поздние коммиты с меньшим ID. Отставание зависит от длительности
 * полного прохода, а не только от интервала планировщика.
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
     * @return сколько заказов прочитано из PostgreSQL; ноль означает конец текущего прохода
     * и сброс курсора для следующего. Изменённые ранее заказы проверит следующий проход.
     */
    public synchronized int projectNextBatch() {
        long lastProjected = recommendationGraph.lastProjectedOrderId();

        List<Order> batch = orderService.findForProjection(lastProjected, properties.projectionBatch());

        if (batch.isEmpty()) {
            // Следующий такт перечитает старые заказы: их статус мог измениться,
            // а транзакция с меньшим ID могла завершиться после продвижения курсора.
            recommendationGraph.projectOrders(List.of(), 0L);
            return 0;
        }

        // Метка двигается по последнему прочитанному заказу, а не по последнему
        // спроецированному: отменённые заказы тоже обработаны.
        long lastRead = batch.get(batch.size() - 1).id();

        recommendationGraph.projectOrders(batch, lastRead);

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
    public synchronized int rebuild() {
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
