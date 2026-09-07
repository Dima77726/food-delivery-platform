package com.dima.fooddelivery.review.service;

import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.review.api.ReviewResponse;
import com.dima.fooddelivery.review.api.ReviewResponseMapper;
import com.dima.fooddelivery.review.domain.DishRating;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.domain.Review;
import com.dima.fooddelivery.review.persistence.ReviewRepository;
import com.dima.fooddelivery.review.persistence.ReviewSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Модуль Review. Отзывы о доставленных заказах.
 *
 * <p><b>Где здесь граница между хранилищами.</b> Заказ живёт в PostgreSQL, отзыв — в MongoDB,
 * и связывает их только {@code orderId}. Право оставить отзыв проверяется через
 * {@link OrderStatusService} — публичный вход модуля Order, — а не запросом в чужую таблицу.
 * Правило то же, что и между остальными модулями; смена хранилища его не отменяет.
 *
 * <p><b>Транзакции здесь нет, и это осознанно.</b> В приложении один менеджер транзакций,
 * JpaTransactionManager, и на запись в MongoDB он не распространяется: {@code @Transactional}
 * над этим методом создал бы опасную иллюзию, что откат заказа отменит и отзыв. Он бы его
 * не отменил. Поэтому порядок действий выбран так, чтобы этого и не потребовалось: сначала
 * проверки по PostgreSQL, ничего не меняющие, и только потом единственная запись в MongoDB.
 * Худшее, что может случиться, — отзыв записан, а ответ до клиента не доехал; повтор запроса
 * упрётся в уникальный индекс и вернёт внятную ошибку вместо второго отзыва.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.MONGO, havingValue = "true")
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewSummaryRepository reviewSummaryRepository;
    private final OrderStatusService orderStatusService;

    /**
     * Оставить отзыв о заказе.
     *
     * @throws ResourceNotFoundException          если заказа нет
     * @throws AccessDeniedForResourceException   если заказ чужой
     * @throws BusinessRuleViolationException     если заказ ещё не доставлен либо отзыв уже есть
     */
    public ReviewResponse createReview(
            Long customerId,
            Long orderId,
            int rating,
            String comment,
            List<DishRating> dishes,
            List<String> tags
    ) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.belongsToCustomer(customerId)) {
            log.warn("Попытка оставить отзыв о чужом заказе: orderId={}, customerId={}", orderId, customerId);

            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        // Отзыв о недоставленном заказе оценивал бы то, чего клиент ещё не видел.
        if (order.status() != OrderStatus.DELIVERED) {
            throw new BusinessRuleViolationException(
                    "Отзыв можно оставить только о доставленном заказе. Текущий статус="
                            + order.status().getDbValue()
            );
        }

        Review review = new Review(
                null,
                orderId,
                order.restaurantId(),
                customerId,
                rating,
                comment,
                dishes == null ? List.of() : dishes,
                tags == null ? List.of() : tags,
                Instant.now()
        );

        try {
            Review saved = reviewRepository.save(review);

            log.info(
                    "Отзыв сохранён: reviewId={}, orderId={}, restaurantId={}, rating={}",
                    saved.id(), orderId, order.restaurantId(), rating
            );

            return ReviewResponseMapper.toResponse(saved);
        } catch (DuplicateKeyException duplicate) {
            // Сработал uq_review_order. Проверять наличие отзыва заранее бессмысленно:
            // между проверкой и записью помещается второй такой же запрос.
            log.warn("Повторный отзыв о заказе: orderId={}", orderId);

            throw new BusinessRuleViolationException("Отзыв о заказе с id=" + orderId + " уже оставлен");
        }
    }

    /**
     * Отзывы ресторана, свежие сверху.
     *
     * <p>Постраничное листание, а не курсорное, — сознательное отличие от ленты заказов.
     * Отзывы читают глазами и редко дальше второй страницы, зато общее число («отзывов: 128»)
     * нужно показать всегда, а курсорная модель его не даёт.
     */
    public PageResponse<ReviewResponse> getRestaurantReviews(Long restaurantId, PageRequestParams page) {
        List<Review> reviews = reviewRepository.findByRestaurantId(
                restaurantId,
                PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        return PageResponse.of(
                ReviewResponseMapper.toResponses(reviews),
                page.page(),
                page.size(),
                reviewRepository.countByRestaurantId(restaurantId)
        );
    }

    public RatingSummary getSummary(Long restaurantId) {
        return reviewSummaryRepository.summarize(restaurantId);
    }
}
