package com.dima.fooddelivery.review.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Отзыв о заказе. Документ MongoDB.
 *
 * <p><b>Почему не PostgreSQL.</b> У отзыва подвижная форма: сегодня это оценка и текст, завтра
 * — оценки отдельных блюд, теги, фотографии, ответ ресторана. В реляционной схеме каждое такое
 * «завтра» — миграция и ещё одна таблица со своим join'ом; здесь новое поле не стоит ничего,
 * а вложенные оценки блюд лежат внутри того же документа и читаются одним обращением.
 *
 * <p>Цена решения тоже понятна и принята: у документа нет внешних ключей. Ни база, ни драйвер
 * не помешают сохранить отзыв на несуществующий заказ — целостность держит
 * {@code ReviewService}, проверяя заказ через модуль Order перед записью. Ровно поэтому здесь
 * не хранится ничего, за что отвечает PostgreSQL: сумма заказа, состав, статус остаются там,
 * а тут лежат только идентификаторы, чтобы можно было сходить за подробностями.
 *
 * <p><b>Время — {@link Instant}, а не {@code OffsetDateTime}.</b> В BSON тип даты один и хранит
 * момент в UTC; смещения ему просто негде разместиться. {@code OffsetDateTime} стандартные
 * конвертеры Spring Data MongoDB не поддерживают, и попытка сохранить его кончается ошибкой
 * маппинга при первой же записи. Обратно в {@code OffsetDateTime} значение превращает маппер
 * на границе API.
 *
 * <p>Record, а не класс: документ неизменяем после создания, а Spring Data умеет собирать
 * его через канонический конструктор.
 */
@Document(collection = Review.COLLECTION)
public record Review(

        @Id
        String id,

        /**
         * Ключ идемпотентности: один заказ — один отзыв. Уникальный индекс на это поле
         * создаёт {@code ReviewIndexes}, и он же ловит гонку двух одновременных отправок.
         */
        Long orderId,

        Long restaurantId,

        Long customerId,

        /** Общая оценка заказа, 1..5. Границы проверяются на входе в API. */
        int rating,

        String comment,

        /** Оценки отдельных блюд. Пустой список — штатный случай: их можно не ставить. */
        List<DishRating> dishes,

        /** Свободные метки вроде «быстро» или «холодная еда». По ним считается топ в сводке. */
        List<String> tags,

        Instant createdAt
) {

    public static final String COLLECTION = "review";

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
}
