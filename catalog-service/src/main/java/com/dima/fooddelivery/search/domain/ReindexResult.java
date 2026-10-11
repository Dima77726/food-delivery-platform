package com.dima.fooddelivery.search.domain;

/**
 * Итог переиндексации.
 *
 * @param restaurants сколько ресторанов записано в индекс
 * @param items       сколько блюд записано в индекс
 * @param removed     сколько документов убрано как устаревшие
 */
public record ReindexResult(
        int restaurants,
        int items,
        long removed
) {
}
