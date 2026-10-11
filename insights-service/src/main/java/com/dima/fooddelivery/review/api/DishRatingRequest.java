package com.dima.fooddelivery.review.api;

import com.dima.fooddelivery.review.domain.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Оценка одного блюда внутри запроса на отзыв. */
public record DishRatingRequest(

        @NotNull(message = "menuItemId обязателен")
        @Positive(message = "menuItemId должен быть положительным числом")
        Long menuItemId,

        @NotBlank(message = "название блюда обязательно")
        @Size(max = 255, message = "название блюда не длиннее 255 символов")
        String menuItemName,

        @NotNull(message = "оценка обязательна")
        @Min(value = Review.MIN_RATING, message = "оценка не меньше " + Review.MIN_RATING)
        @Max(value = Review.MAX_RATING, message = "оценка не больше " + Review.MAX_RATING)
        Integer rating
) {
}
