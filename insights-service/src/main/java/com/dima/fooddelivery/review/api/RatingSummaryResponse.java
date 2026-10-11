package com.dima.fooddelivery.review.api;

import java.util.List;
import java.util.Map;

/** Сводка по отзывам ресторана в ответе API. */
public record RatingSummaryResponse(
        Long restaurantId,
        long total,
        double average,
        Map<Integer, Long> histogram,
        List<TagCountResponse> topTags
) {

    public record TagCountResponse(String tag, long count) {
    }
}
