package com.dima.fooddelivery.recommendation.domain;

/**
 * Рекомендованное блюдо и вес, с которым его посоветовали.
 *
 * <p>{@code score} — не вероятность и не оценка качества, а просто счётчик совпадений:
 * сколько человек или сколько заказов подтвердили связь. Он нужен для порядка выдачи
 * и объяснения «почему это здесь», и сравнивать его между разными запросами бессмысленно.
 */
public record RecommendedItem(
        Long menuItemId,
        String name,
        Long restaurantId,
        long score
) {
}
