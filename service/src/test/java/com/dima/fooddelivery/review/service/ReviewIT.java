package com.dima.fooddelivery.review.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.review.api.ReviewResponse;
import com.dima.fooddelivery.review.domain.DishRating;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.support.AbstractStoresIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Отзывы на настоящем MongoDB.
 *
 * <p>Проверяется то, что на моках проверить нельзя: срабатывание уникального индекса
 * по заказу и результат aggregation pipeline. Первое — единственная защита от двух отзывов
 * на один заказ, второе — сам смысл модуля.
 *
 * <p>Изоляция между тестами держится на уникальности идентификаторов, а не на очистке базы:
 * ресторан и заказ каждый тест создаёт свои, а откат транзакции PostgreSQL номера
 * последовательностей не возвращает. Документы прошлых прогонов в коллекции остаются
 * и ни на что не влияют — они относятся к другим ресторанам.
 */
class ReviewIT extends AbstractStoresIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Test
    void shouldSaveReviewForDeliveredOrder() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.DELIVERED);

        ReviewResponse response = reviewService.createReview(
                customerId,
                order.orderId(),
                5,
                "Привезли горячим",
                List.of(new DishRating(order.menuItemId(), "Тестовое блюдо", 5)),
                List.of("быстро", "вкусно")
        );

        assertAll(
                () -> assertNotNull(response.id(), "MongoDB обязан проставить идентификатор документа"),
                () -> assertEquals(order.orderId(), response.orderId()),
                () -> assertEquals(order.restaurantId(), response.restaurantId()),
                () -> assertEquals(5, response.rating()),
                () -> assertEquals(1, response.dishes().size(), "вложенные оценки блюд должны сохраниться"),
                () -> assertEquals(List.of("быстро", "вкусно"), response.tags()),
                () -> assertNotNull(response.createdAt(), "время должно вернуться со смещением")
        );
    }

    @Test
    void shouldRejectSecondReviewForSameOrder() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.DELIVERED);

        reviewService.createReview(customerId, order.orderId(), 4, "Нормально", List.of(), List.of());

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> reviewService.createReview(customerId, order.orderId(), 1, "Передумал", List.of(), List.of())
        );

        assertTrue(
                exception.getMessage().contains(String.valueOf(order.orderId())),
                "в сообщении должен быть номер заказа, иначе клиенту непонятно, о чём речь"
        );
    }

    @Test
    void shouldRejectReviewForOrderThatIsNotDeliveredYet() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.COOKING);

        assertThrows(
                BusinessRuleViolationException.class,
                () -> reviewService.createReview(customerId, order.orderId(), 5, "Заранее", List.of(), List.of())
        );
    }

    @Test
    void shouldRejectReviewForForeignOrder() {
        Long owner = testData.insertCustomer();
        Long stranger = testData.insertCustomer();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(owner, OrderStatus.DELIVERED);

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> reviewService.createReview(stranger, order.orderId(), 1, "Не мой заказ", List.of(), List.of())
        );
    }

    /**
     * Главная проверка модуля: конвейер с facet считает три независимых ответа за один проход.
     *
     * <p>Четыре отзыва с оценками 5, 5, 4 и 2 дают среднее 4.0, гистограмму из трёх корзин
     * и метку «быстро», встретившуюся трижды. Каждое из этих чисел приходит из своей подветки
     * конвейера, и расхождение между ними означало бы ошибку именно в нём.
     */
    @Test
    void shouldSummarizeRestaurantRatings() {
        Long restaurantId = testData.insertRestaurant();

        reviewOrder(restaurantId, 5, List.of("быстро", "вкусно"));
        reviewOrder(restaurantId, 5, List.of("быстро"));
        reviewOrder(restaurantId, 4, List.of("быстро"));
        reviewOrder(restaurantId, 2, List.of("холодное"));

        RatingSummary summary = reviewService.getSummary(restaurantId);

        assertAll(
                () -> assertEquals(4L, summary.total()),
                () -> assertEquals(4.0, summary.average(), 0.0001),
                () -> assertEquals(2L, summary.histogram().get(5), "две пятёрки"),
                () -> assertEquals(1L, summary.histogram().get(4)),
                () -> assertEquals(1L, summary.histogram().get(2)),
                () -> assertEquals(
                        "быстро",
                        summary.topTags().get(0).tag(),
                        "самая частая метка обязана быть первой"
                ),
                () -> assertEquals(3L, summary.topTags().get(0).count())
        );
    }

    @Test
    void shouldReturnEmptySummaryForRestaurantWithoutReviews() {
        RatingSummary summary = reviewService.getSummary(testData.insertRestaurant());

        assertAll(
                () -> assertEquals(0L, summary.total()),
                () -> assertEquals(0.0, summary.average(), 0.0001),
                () -> assertTrue(summary.histogram().isEmpty()),
                () -> assertTrue(summary.topTags().isEmpty())
        );
    }

    /**
     * Доставленный заказ в заданном ресторане и отзыв о нём.
     *
     * <p>{@code insertOrderInStatus} здесь не подходит: он каждый раз создаёт новый ресторан,
     * а сводку нужно считать по нескольким отзывам об одном.
     */
    private void reviewOrder(Long restaurantId, int rating, List<String> tags) {
        Long customerId = testData.insertCustomer();
        BigDecimal price = new BigDecimal("450.00");

        Long cartId = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
        Long orderId = testData.insertOrder(
                cartId, customerId, restaurantId, OrderStatus.DELIVERED, price
        );

        reviewService.createReview(customerId, orderId, rating, null, List.of(), tags);
    }
}
