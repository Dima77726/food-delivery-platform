package com.dima.fooddelivery.review.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Отзыв в ответе API.
 *
 * <p>Отдельный тип от документа {@code Review} по той же причине, по которой
 * {@code MenuItemView} отделён от сущности: форма хранения и форма контракта обязаны меняться
 * независимо. В документной базе это даже важнее — новое поле там появляется без миграции,
 * и без явного типа ответа оно уехало бы клиенту в тот же день, никем не замеченное.
 */
public record ReviewResponse(
        String id,
        Long orderId,
        Long restaurantId,
        Long customerId,
        int rating,
        String comment,
        List<DishRatingResponse> dishes,
        List<String> tags,
        OffsetDateTime createdAt
) {

    public record DishRatingResponse(
            Long menuItemId,
            String menuItemName,
            int rating
    ) {
    }
}
