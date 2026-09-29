package com.dima.fooddelivery.recommendation.persistence;

import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderItem;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Граф заказов в Neo4j: проекция и запросы рекомендаций. Cypher написан руками.
 *
 * <p><b>Модель графа.</b> Четыре типа узлов и три типа связей:
 * <pre>
 *   (:Customer {id})-[:ORDERED {orderId, at, quantity}]-&gt;(:Dish {id, name, restaurantId})
 *   (:Dish)-[:SERVED_BY]-&gt;(:Restaurant {id})
 *   (:ProjectionState {name, lastOrderId})
 * </pre>
 *
 * <p>Смысл модели в том, что связь несёт номер заказа. Без него нельзя было бы ответить
 * на вопрос «что заказывают вместе»: две связи одного клиента к разным блюдам сами по себе
 * не говорят, было это в одном заказе или в разных, с разницей в полгода.
 *
 * <p><b>Почему для этого нужна графовая база.</b> Рекомендация «люди, заказывавшие то же,
 * что и вы, брали ещё вот это» — обход на два шага по связям. В SQL это самосоединение
 * таблицы позиций заказа с собой дважды, по миллионам строк, с группировкой поверх.
 * Работать оно будет, но каждый следующий шаг обхода добавляет ещё одно соединение,
 * и уже на трёх шагах запрос перестаёт быть читаемым, а план — предсказуемым. В Cypher
 * шаг обхода - это одна стрелка в шаблоне, а стоимость его зависит от числа связей
 * у конкретного узла, а не от размера таблицы.
 *
 * <p><b>Граф производный, как и витрина аналитики.</b> Система записи — PostgreSQL, сюда
 * заказы приезжают проекцией. Метка прочитанного хранится тут же, узлом ProjectionState:
 * заводить ради одного числа таблицу в PostgreSQL значило бы связать два хранилища так,
 * что удалить граф целиком стало бы нельзя.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.NEO4J, havingValue = "true")
public class RecommendationGraph {

    /** Имя метки прочитанного. Проекция пока одна, но узел адресуется по имени. */
    public static final String ORDERS_PROJECTION = "orders";

    /**
     * Проекция пачки заказов.
     *
     * <p>Весь Cypher идёт одним запросом с UNWIND по списку, а не запросом на заказ.
     * Двести отдельных запросов — это двести обращений к серверу; здесь оно одно.
     *
     * <p>Каждый шаг — MERGE, а не CREATE, и это делает проекцию идемпотентной: повторный
     * проход по тем же заказам не создаст ни второго узла блюда, ни второй связи. Ключ
     * идемпотентности связи — {@code orderId} в её свойствах: один заказ порождает ровно
     * одну связь клиента с блюдом.
     */
    private static final String PROJECT_ORDERS = """
            UNWIND $orders AS o
            MERGE (c:Customer {id: o.customerId})
            MERGE (r:Restaurant {id: o.restaurantId})
            WITH c, r, o
            UNWIND o.items AS item
            MERGE (d:Dish {id: item.id})
              ON CREATE SET d.name = item.name, d.restaurantId = o.restaurantId
              ON MATCH  SET d.name = item.name
            MERGE (d)-[:SERVED_BY]->(r)
            MERGE (c)-[ordered:ORDERED {orderId: o.orderId}]->(d)
              ON CREATE SET ordered.at = o.createdAt, ordered.quantity = item.quantity
            """;

    /**
     * Совместная фильтрация: что брали те, кто заказывал то же самое.
     *
     * <p>Шаблон читается слева направо как маршрут по графу: от меня к моим блюдам,
     * от них — к другим клиентам, от них — к их блюдам. Условие NOT (me)-[:ORDERED]-&gt;(rec)
     * убирает то, что я и так уже пробовал: рекомендовать человеку блюдо, которое он заказывал
     * на прошлой неделе, — не рекомендация.
     *
     * <p>{@code count(DISTINCT other)} считает людей, а не заказы. Иначе один постоянный
     * клиент с полусотней заказов в одиночку определял бы выдачу для всех остальных.
     */
    private static final String RECOMMEND_FOR_CUSTOMER = """
            MATCH (me:Customer {id: $customerId})-[:ORDERED]->(mine:Dish)
            MATCH (mine)<-[:ORDERED]-(other:Customer)-[:ORDERED]->(rec:Dish)
            WHERE other.id <> $customerId
              AND NOT (me)-[:ORDERED]->(rec)
            RETURN rec.id           AS menuItemId,
                   rec.name         AS name,
                   rec.restaurantId AS restaurantId,
                   count(DISTINCT other) AS score
            ORDER BY score DESC, name
            LIMIT $limit
            """;

    /**
     * Что заказывают вместе с этим блюдом.
     *
     * <p>Ключевое здесь — {@code r2.orderId = r1.orderId}: обе связи обязаны принадлежать
     * одному заказу. Без этого условия ответ превратился бы в «что ещё когда-либо заказывали
     * эти люди», то есть примерно в список самых популярных блюд.
     */
    private static final String ORDERED_TOGETHER = """
            MATCH (c:Customer)-[r1:ORDERED]->(:Dish {id: $menuItemId})
            MATCH (c)-[r2:ORDERED]->(other:Dish)
            WHERE r2.orderId = r1.orderId
              AND other.id <> $menuItemId
            RETURN other.id           AS menuItemId,
                   other.name         AS name,
                   other.restaurantId AS restaurantId,
                   count(*)           AS score
            ORDER BY score DESC, name
            LIMIT $limit
            """;

    private final Driver driver;

    /**
     * Ограничения уникальности на старте.
     *
     * <p>В Neo4j это не только защита от дублей, но и индекс: MERGE по узлу без ограничения
     * на свойство приводит к полному перебору узлов метки. С ограничением он находит узел
     * по индексу. На проекции в двести заказов разница между этими двумя вариантами
     * измеряется секундами.
     *
     * <p>Заодно это первое настоящее обращение к серверу: если Neo4j недоступен, приложение
     * упадёт здесь, с понятным сообщением, а не при первом запросе рекомендаций.
     */
    @PostConstruct
    void createConstraints() {
        List<String> constraints = List.of(
                "CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE",
                "CREATE CONSTRAINT dish_id IF NOT EXISTS FOR (d:Dish) REQUIRE d.id IS UNIQUE",
                "CREATE CONSTRAINT restaurant_id IF NOT EXISTS FOR (r:Restaurant) REQUIRE r.id IS UNIQUE",
                "CREATE CONSTRAINT projection_name IF NOT EXISTS FOR (p:ProjectionState) REQUIRE p.name IS UNIQUE"
        );

        try (Session session = driver.session()) {
            constraints.forEach(cypher -> session.executeWrite(tx -> tx.run(cypher).consume()));
        }

        log.info("Ограничения графа Neo4j проверены");
    }

    /**
     * Проецирует заказы и сдвигает метку прочитанного.
     *
     * <p>Замена связей и метка обновляются в одной транзакции. При сбое пачку можно
     * повторить целиком; читатель не видит промежуточного удаления связей.
     *
     * <p>Отменённые заказы передаются в пачке тоже: их прежние связи нужно удалить.
     * Пустой список с меткой 0 завершает проход и возвращает курсор к началу.
     */
    public void projectOrders(List<Order> orders, long lastOrderId) {
        List<Map<String, Object>> payload = orders.stream()
                .filter(order -> order.status() != OrderStatus.CANCELED)
                .filter(order -> !order.items().isEmpty())
                .map(RecommendationGraph::toParameters)
                .toList();

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // Удаление и повторная запись атомарны для читателей. Отменённый
                // заказ удаляет прежние связи, но новых уже не создаёт.
                tx.run("""
                        UNWIND $orders AS o
                        MATCH (:Customer {id: o.customerId})-[ordered:ORDERED {orderId: o.orderId}]->()
                        DELETE ordered
                        """, Map.of("orders", orders.stream().map(order -> Map.of(
                                "customerId", order.customerId(), "orderId", order.id()
                        )).toList())).consume();
                if (!payload.isEmpty()) {
                    tx.run(PROJECT_ORDERS, Map.of("orders", payload)).consume();
                }

                tx.run(
                        "MERGE (p:ProjectionState {name: $name}) SET p.lastOrderId = $lastOrderId",
                        Map.of("name", ORDERS_PROJECTION, "lastOrderId", lastOrderId)
                ).consume();

                return null;
            });
        }

        log.info("В граф спроецировано {} заказов, метка прочитанного: {}", orders.size(), lastOrderId);
    }

    /** @return идентификатор последнего спроецированного заказа, 0 если проекции ещё не было */
    public long lastProjectedOrderId() {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Record> records = tx.run(
                        "MATCH (p:ProjectionState {name: $name}) RETURN p.lastOrderId AS lastOrderId",
                        Map.of("name", ORDERS_PROJECTION)
                ).list();

                return records.isEmpty() ? 0L : records.get(0).get("lastOrderId").asLong(0L);
            });
        }
    }

    /** Сбрасывает граф целиком. Нужен для полного перестроения проекции. */
    public void clear() {
        try (Session session = driver.session()) {
            // DETACH DELETE удаляет узел вместе с его связями. Обычный DELETE на узле со
            // связями падает: висячих связей в Neo4j не бывает по определению.
            session.executeWrite(tx -> tx.run("MATCH (n) DETACH DELETE n").consume());
        }

        log.warn("Граф рекомендаций очищен, проекция начнётся заново");
    }

    public List<RecommendedItem> recommendForCustomer(Long customerId, int limit) {
        return query(RECOMMEND_FOR_CUSTOMER, Map.of("customerId", customerId, "limit", limit));
    }

    public List<RecommendedItem> orderedTogetherWith(Long menuItemId, int limit) {
        return query(ORDERED_TOGETHER, Map.of("menuItemId", menuItemId, "limit", limit));
    }

    private List<RecommendedItem> query(String cypher, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(cypher, parameters)
                    .list(record -> new RecommendedItem(
                            record.get("menuItemId").asLong(),
                            record.get("name").asString(),
                            record.get("restaurantId").asLong(),
                            record.get("score").asLong()
                    )));
        }
    }

    /**
     * Заказ в виде вложенной карты — того, что Cypher примет как параметр и развернёт UNWIND.
     *
     * <p>Карта, а не доменный объект: драйвер Neo4j умеет отдавать в параметры только скаляры,
     * списки и карты. Маппинг сущностей — это как раз то, что даёт Spring Data Neo4j
     * и от чего мы здесь сознательно отказались.
     */
    private static Map<String, Object> toParameters(Order order) {
        Map<String, Object> parameters = new HashMap<>();

        parameters.put("orderId", order.id());
        parameters.put("customerId", order.customerId());
        parameters.put("restaurantId", order.restaurantId());
        parameters.put("createdAt", order.createdAt());
        parameters.put("items", order.items().stream().map(RecommendationGraph::toParameters).toList());

        return parameters;
    }

    private static Map<String, Object> toParameters(OrderItem item) {
        Map<String, Object> parameters = new HashMap<>();

        parameters.put("id", item.menuItemId());
        parameters.put("name", item.menuItemName());
        parameters.put("quantity", item.quantity());

        return parameters;
    }
}
