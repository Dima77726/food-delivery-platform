package com.dima.fooddelivery.search.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * Блюдо в поисковом индексе.
 *
 * <p>Документ денормализован: название ресторана и город скопированы сюда из соседней
 * таблицы. В реляционной схеме это было бы дублированием и поводом для JOIN, здесь —
 * норма и необходимость. Поиск обязан отвечать одним обращением; сходить в индекс за
 * блюдами, а потом в PostgreSQL за названиями их ресторанов означало бы вернуть ту самую
 * задачу N+1, от которой в остальном приложении так старательно уходят.
 *
 * <p>Плата за денормализацию — переименование ресторана требует переиндексации его блюд.
 * Здесь это происходит само: индекс перестраивается целиком по расписанию.
 *
 * <p><b>Цена — примитивный double, а не BigDecimal.</b> Единственное её назначение здесь —
 * показаться в карточке результата и, возможно, поучаствовать в сортировке. Деньги
 * считаются в PostgreSQL, где цена лежит в NUMERIC; тащить точный десятичный тип в индекс,
 * который всё равно хранит числа в JSON, незачем.
 */
@Document(indexName = MenuItemDocument.INDEX)
public record MenuItemDocument(

        @Id
        String id,

        @Field(type = FieldType.Long)
        Long menuItemId,

        @Field(type = FieldType.Long)
        Long restaurantId,

        @Field(type = FieldType.Text, analyzer = "russian")
        String restaurantName,

        @Field(type = FieldType.Keyword)
        String city,

        @Field(type = FieldType.Text, analyzer = "russian")
        String name,

        @Field(type = FieldType.Text, analyzer = "russian")
        String description,

        @Field(type = FieldType.Double)
        double price,

        @Field(type = FieldType.Boolean)
        boolean available,

        /** Два формата — по той же причине, что и в {@link RestaurantDocument}. */
        @Field(type = FieldType.Date, format = {DateFormat.date_time, DateFormat.epoch_millis})
        Instant indexedAt
) {

    public static final String INDEX = "food-delivery-menu-items";

    public static String documentId(Long menuItemId) {
        return String.valueOf(menuItemId);
    }
}
