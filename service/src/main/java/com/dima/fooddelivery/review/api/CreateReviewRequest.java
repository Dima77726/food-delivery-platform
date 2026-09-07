package com.dima.fooddelivery.review.api;

import com.dima.fooddelivery.review.domain.Review;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Тело запроса на создание отзыва.
 *
 * <p>Гибкая схема документа не означает отсутствие проверок на входе. Наоборот: раз база
 * примет что угодно, единственное место, где форма отзыва вообще контролируется, — вот эта
 * запись. В PostgreSQL часть работы взяли бы на себя NOT NULL и CHECK, здесь их нет.
 */
public record CreateReviewRequest(

        @NotNull(message = "оценка обязательна")
        @Min(value = Review.MIN_RATING, message = "оценка не меньше " + Review.MIN_RATING)
        @Max(value = Review.MAX_RATING, message = "оценка не больше " + Review.MAX_RATING)
        @Schema(description = "Общая оценка заказа", example = "5")
        Integer rating,

        @Size(max = 2000, message = "комментарий не длиннее 2000 символов")
        @Schema(description = "Текст отзыва", example = "Привезли горячим, курьер вежливый")
        String comment,

        @Valid
        @Size(max = 50, message = "не больше 50 оценок блюд")
        @Schema(description = "Оценки отдельных блюд заказа")
        List<DishRatingRequest> dishes,

        @Size(max = 10, message = "не больше 10 меток")
        @Schema(description = "Короткие метки", example = "[\"быстро\", \"вкусно\"]")
        List<@Size(max = 32, message = "метка не длиннее 32 символов") String> tags
) {
}
