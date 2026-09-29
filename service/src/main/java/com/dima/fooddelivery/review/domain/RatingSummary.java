package com.dima.fooddelivery.review.domain;

import java.util.List;
import java.util.Map;

/**
 * Сводка по отзывам ресторана: то, что считает aggregation pipeline.
 *
 * <p>Тип принадлежит домену, а не слою persistence, потому что это не форма строки в базе,
 * а результат вопроса «как ресторан выглядит в глазах клиентов». Считать его на лету
 * по всем отзывам можно ровно до тех пор, пока отзывов немного; когда их станут сотни тысяч,
 * этот же тип начнёт заполняться из предпосчитанной коллекции, и ни API, ни сервис
 * об этом не узнают.
 */
public record RatingSummary(

        Long restaurantId,

        /** Сколько всего отзывов учтено. Ноль означает, что ресторан ещё не оценивали. */
        long total,

        /** Средняя оценка. При нулевом количестве отзывов равна 0. */
        double average,

        /** Сколько отзывов на каждую оценку: 5 → 120, 4 → 30 и так далее. */
        Map<Integer, Long> histogram,

        /** Самые частые метки, от частой к редкой. */
        List<TagCount> topTags
) {

    public record TagCount(String tag, long count) {
    }

    public static RatingSummary empty(Long restaurantId) {
        return new RatingSummary(restaurantId, 0L, 0.0, Map.of(), List.of());
    }
}
