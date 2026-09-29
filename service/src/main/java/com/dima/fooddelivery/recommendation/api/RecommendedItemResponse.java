package com.dima.fooddelivery.recommendation.api;

import com.dima.fooddelivery.recommendation.domain.RecommendedItem;

import java.util.List;

/**
 * Рекомендованное блюдо в ответе API.
 *
 * <p>{@code score} отдаётся наружу намеренно. Рекомендация без объяснения выглядит как
 * произвол алгоритма; счётчик совпадений позволяет клиенту показать «это брали ещё
 * 12 человек с похожими заказами» — а это уже довод, а не магия.
 */
public record RecommendedItemResponse(
        Long menuItemId,
        String name,
        Long restaurantId,
        long score
) {

    public static RecommendedItemResponse of(RecommendedItem item) {
        return new RecommendedItemResponse(item.menuItemId(), item.name(), item.restaurantId(), item.score());
    }

    public static List<RecommendedItemResponse> of(List<RecommendedItem> items) {
        return items.stream().map(RecommendedItemResponse::of).toList();
    }
}
