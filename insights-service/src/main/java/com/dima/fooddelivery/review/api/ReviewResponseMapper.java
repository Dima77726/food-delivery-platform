package com.dima.fooddelivery.review.api;

import com.dima.fooddelivery.review.domain.DishRating;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.domain.Review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Перевод документов и сводок в типы контракта.
 *
 * <p>Здесь же происходит обратное превращение времени: в BSON оно хранится моментом в UTC,
 * а наружу отдаётся в том же виде, что и все остальные даты приложения, — со смещением
 * часового пояса приложения. Иначе одна и та же дата в ответе выглядела бы по-разному
 * в зависимости от того, из какой базы она приехала.
 */
public final class ReviewResponseMapper {

    public static ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.id(),
                review.orderId(),
                review.restaurantId(),
                review.customerId(),
                review.rating(),
                review.comment(),
                toDishResponses(review.dishes()),
                review.tags() == null ? List.of() : review.tags(),
                OffsetDateTime.ofInstant(review.createdAt(), ZoneId.systemDefault())
        );
    }

    public static List<ReviewResponse> toResponses(List<Review> reviews) {
        return reviews.stream().map(ReviewResponseMapper::toResponse).toList();
    }

    public static RatingSummaryResponse toResponse(RatingSummary summary) {
        return new RatingSummaryResponse(
                summary.restaurantId(),
                summary.total(),
                // Две цифры после запятой: 4.3333333333333 в ответе не значит ничего,
                // кроме того, что округлить забыли.
                BigDecimal.valueOf(summary.average()).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                summary.histogram(),
                summary.topTags().stream()
                        .map(tag -> new RatingSummaryResponse.TagCountResponse(tag.tag(), tag.count()))
                        .toList()
        );
    }

    public static List<DishRating> toDishRatings(List<DishRatingRequest> requests) {
        if (requests == null) {
            return List.of();
        }

        return requests.stream()
                .map(request -> new DishRating(
                        request.menuItemId(),
                        request.menuItemName().trim(),
                        request.rating()
                ))
                .toList();
    }

    private static List<ReviewResponse.DishRatingResponse> toDishResponses(List<DishRating> dishes) {
        if (dishes == null) {
            return List.of();
        }

        return dishes.stream()
                .map(dish -> new ReviewResponse.DishRatingResponse(
                        dish.menuItemId(),
                        dish.menuItemName(),
                        dish.rating()
                ))
                .toList();
    }

    private ReviewResponseMapper() {
    }
}
