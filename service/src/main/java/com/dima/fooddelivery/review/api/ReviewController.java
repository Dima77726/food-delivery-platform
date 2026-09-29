package com.dima.fooddelivery.review.api;

import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Отзывы: запись клиентом, чтение витриной.
 *
 * <p>Контроллер, как и весь модуль, существует только при включённом MongoDB. Выключенное
 * хранилище означает не 500 и не пустой ответ, а отсутствие маршрута: 404. Это честнее —
 * функциональности в такой сборке действительно нет.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.MONGO, havingValue = "true")
@Tag(name = "Review", description = "Отзывы о заказах (MongoDB)")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Оставить отзыв о доставленном заказе")
    public ReviewResponse createReview(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId,

            @Valid @RequestBody CreateReviewRequest request
    ) {
        log.info("Отзыв о заказе: customerId={}, orderId={}, rating={}", customerId, orderId, request.rating());

        return reviewService.createReview(
                customerId,
                orderId,
                request.rating(),
                request.comment(),
                ReviewResponseMapper.toDishRatings(request.dishes()),
                request.tags()
        );
    }

    /**
     * Открыто без токена: отзывы — часть витрины, ради которой пользователь и приходит.
     * Разрешение выдано в {@code SecurityConfig}, здесь только отсутствие {@code @PreAuthorize}.
     */
    @GetMapping("/api/v1/restaurants/{restaurantId}/reviews")
    @Operation(summary = "Отзывы о ресторане, свежие сверху")
    public PageResponse<ReviewResponse> getRestaurantReviews(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Valid PageRequestParams page
    ) {
        return reviewService.getRestaurantReviews(restaurantId, page);
    }

    @GetMapping("/api/v1/restaurants/{restaurantId}/reviews/summary")
    @Operation(summary = "Сводка по отзывам: среднее, гистограмма оценок, частые метки")
    public RatingSummaryResponse getRestaurantRatingSummary(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        return ReviewResponseMapper.toResponse(reviewService.getSummary(restaurantId));
    }
}
