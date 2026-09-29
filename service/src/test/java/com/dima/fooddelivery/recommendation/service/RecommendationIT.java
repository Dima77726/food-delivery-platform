package com.dima.fooddelivery.recommendation.service;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.recommendation.persistence.RecommendationGraph;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import com.dima.fooddelivery.support.AbstractStoresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рекомендации на настоящем Neo4j.
 *
 * <p>Проверяется то, ради чего графовая база вообще взята: обход на два шага
 * (я → мои блюда → другие клиенты → их блюда) и совместная встречаемость внутри одного
 * заказа. Оба запроса невозможно проверить без графа, и оба легко написать так, что они
 * будут возвращать правдоподобную чушь — например, просто самые популярные блюда.
 *
 * <p>Граф очищается перед каждым тестом. Это единственный из пяти модулей, где очистка
 * действительно нужна: рекомендации считаются по всему графу целиком, и заказы, оставшиеся
 * от соседнего теста, влияли бы на результат.
 */
class RecommendationIT extends AbstractStoresIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("450.00");

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RecommendationGraph graph;

    @BeforeEach
    void clearGraph() {
        recommendationService.rebuild();
    }

    /**
     * Классическая совместная фильтрация. Двое клиентов заказывали одно и то же блюдо,
     * второй вдобавок брал ещё одно — оно и должно попасть в рекомендации первому.
     */
    @Test
    void shouldRecommendDishesOrderedByCustomersWithSimilarTaste() {
        Long restaurantId = testData.insertRestaurant();

        Long shared = testData.insertMenuItem(restaurantId, PRICE);
        Long extra = testData.insertMenuItem(restaurantId, PRICE);

        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();

        placeOrder(me, restaurantId, List.of(shared));
        placeOrder(other, restaurantId, List.of(shared, extra));

        projectEverything();

        List<RecommendedItem> recommendations = recommendationService.recommendForCustomer(me, 10);

        assertAll(
                () -> assertEquals(1, recommendations.size()),
                () -> assertEquals(extra, recommendations.get(0).menuItemId()),
                () -> assertEquals(restaurantId, recommendations.get(0).restaurantId()),
                () -> assertEquals(1L, recommendations.get(0).score(), "связь подтвердил один человек")
        );
    }

    /** Рекомендовать то, что человек уже заказывал, бессмысленно. */
    @Test
    void shouldNotRecommendDishesTheCustomerAlreadyOrdered() {
        Long restaurantId = testData.insertRestaurant();

        Long shared = testData.insertMenuItem(restaurantId, PRICE);

        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();

        placeOrder(me, restaurantId, List.of(shared));
        placeOrder(other, restaurantId, List.of(shared));

        projectEverything();

        assertTrue(
                recommendationService.recommendForCustomer(me, 10).isEmpty(),
                "единственное общее блюдо я уже пробовал, рекомендовать нечего"
        );
    }

    /**
     * Совместная встречаемость считается внутри заказа. Два блюда, заказанные одним
     * человеком в разное время, парой не считаются — иначе ответ выродился бы в список
     * популярного.
     */
    @Test
    void shouldFindDishesOrderedInTheSameOrder() {
        Long restaurantId = testData.insertRestaurant();

        Long pizza = testData.insertMenuItem(restaurantId, PRICE);
        Long cola = testData.insertMenuItem(restaurantId, PRICE);
        Long soup = testData.insertMenuItem(restaurantId, PRICE);

        Long customerId = testData.insertCustomer();

        placeOrder(customerId, restaurantId, List.of(pizza, cola));
        placeOrder(customerId, restaurantId, List.of(soup));

        projectEverything();

        List<RecommendedItem> together = recommendationService.orderedTogetherWith(pizza, 10);

        assertAll(
                () -> assertEquals(1, together.size()),
                () -> assertEquals(cola, together.get(0).menuItemId()),
                () -> assertFalse(
                        together.stream().anyMatch(item -> soup.equals(item.menuItemId())),
                        "суп был в другом заказе и парой пицце не приходится"
                )
        );
    }

    /** Отменённый заказ ничего не говорит о вкусах и в граф попадать не должен. */
    @Test
    void shouldIgnoreCanceledOrders() {
        Long restaurantId = testData.insertRestaurant();

        Long shared = testData.insertMenuItem(restaurantId, PRICE);
        Long extra = testData.insertMenuItem(restaurantId, PRICE);

        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();

        placeOrder(me, restaurantId, List.of(shared));
        placeOrder(other, restaurantId, OrderStatus.CANCELED, List.of(shared, extra));

        projectEverything();

        assertTrue(recommendationService.recommendForCustomer(me, 10).isEmpty());
    }

    /**
     * Повторная проекция не должна ни удваивать связи, ни менять веса: все шаги Cypher
     * написаны через MERGE именно ради этого.
     */
    @Test
    void shouldBeIdempotentOnRepeatedProjection() {
        Long restaurantId = testData.insertRestaurant();

        Long shared = testData.insertMenuItem(restaurantId, PRICE);
        Long extra = testData.insertMenuItem(restaurantId, PRICE);

        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();

        placeOrder(me, restaurantId, List.of(shared));
        placeOrder(other, restaurantId, List.of(shared, extra));

        projectEverything();
        long firstScore = recommendationService.recommendForCustomer(me, 10).get(0).score();

        recommendationService.rebuild();
        long secondScore = recommendationService.recommendForCustomer(me, 10).get(0).score();

        assertEquals(firstScore, secondScore, "перестроение графа не должно менять веса");
    }

    /**
     * Догоняет проекцию до конца.
     *
     * <p>Одного вызова мало по построению: {@code projectNextBatch} обрабатывает одну пачку.
     * В тесте заказов заведомо меньше размера пачки, но полагаться на это значит завязать
     * проверку на настройку.
     */
    private void projectEverything() {
        while (recommendationService.projectNextBatch() > 0) {
            // пусто: вся работа внутри условия
        }
    }

    private void placeOrder(Long customerId, Long restaurantId, List<Long> menuItemIds) {
        placeOrder(customerId, restaurantId, OrderStatus.DELIVERED, menuItemIds);
    }

    private Long placeOrder(Long customerId, Long restaurantId, OrderStatus status, List<Long> menuItemIds) {
        Long cartId = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");

        Long orderId = testData.insertOrder(
                cartId,
                customerId,
                restaurantId,
                status,
                PRICE.multiply(BigDecimal.valueOf(menuItemIds.size()))
        );

        menuItemIds.forEach(menuItemId -> testData.insertOrderItem(orderId, menuItemId, 1, PRICE));
        return orderId;
    }

    @Test
    void shouldRemoveOrderCanceledAfterProjection() {
        Long restaurantId = testData.insertRestaurant();
        Long shared = testData.insertMenuItem(restaurantId, PRICE);
        Long extra = testData.insertMenuItem(restaurantId, PRICE);
        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();
        placeOrder(me, restaurantId, List.of(shared));
        Long orderId = placeOrder(other, restaurantId, OrderStatus.CREATED, List.of(shared, extra));

        // Курсор ещё не сброшен: отмена происходит посреди прохода.
        assertTrue(recommendationService.projectNextBatch() > 0);
        assertEquals(1, recommendationService.recommendForCustomer(me, 10).size());
        orderService.cancelOrder(other, orderId);

        projectEverything();
        projectEverything();

        assertTrue(recommendationService.recommendForCustomer(me, 10).isEmpty());
        assertTrue(recommendationService.orderedTogetherWith(shared, 10).isEmpty());
    }

    @Test
    void shouldRevisitOrdersBehindTheCursorWithoutDuplicatingScores() {
        Long restaurantId = testData.insertRestaurant();
        Long shared = testData.insertMenuItem(restaurantId, PRICE);
        Long extra = testData.insertMenuItem(restaurantId, PRICE);
        Long me = testData.insertCustomer();
        Long other = testData.insertCustomer();
        placeOrder(me, restaurantId, List.of(shared));
        Long lateOrder = placeOrder(other, restaurantId, OrderStatus.DELIVERED, List.of(shared, extra));
        // Как при позднем коммите: данные уже видны, но курсор оказался впереди них.
        graph.projectOrders(List.of(), lateOrder);
        assertEquals(0, recommendationService.projectNextBatch());

        projectEverything();
        assertEquals(1L, recommendationService.recommendForCustomer(me, 10).get(0).score());
        projectEverything();
        assertEquals(1L, recommendationService.recommendForCustomer(me, 10).get(0).score());
    }
}
