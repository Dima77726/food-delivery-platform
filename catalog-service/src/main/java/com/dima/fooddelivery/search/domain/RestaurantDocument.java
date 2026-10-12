package com.dima.fooddelivery.search.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * Ресторан в поисковом индексе.
 *
 * <p><b>Зачем отдельная копия того, что уже есть в PostgreSQL.</b> Запрос «пицца» должен
 * находить и «Пиццерию», и «пиццу пепперони», и опечатку «пицца» через «а». В PostgreSQL это
 * решается через LIKE со звёздочками (не пользуется индексом и не знает про морфологию),
 * через tsvector (знает про морфологию, но не про опечатки) или через pg_trgm (знает
 * про опечатки, но не про морфологию). Elasticsearch делает всё это одним запросом,
 * потому что для него разбор текста на токены — основная работа, а не расширение.
 *
 * <p>Индекс — производные данные, как и витрина аналитики, и как граф рекомендаций. Он
 * не источник истины ни для чего: цена в результате поиска показывается для ориентира,
 * а в корзину блюдо кладётся по цене из PostgreSQL.
 *
 * <p><b>Типы полей заданы явно.</b> Различие между {@code Text} и {@code Keyword} —
 * самое важное в маппинге. Text разбирается анализатором на токены и годится для поиска
 * по словам, но не для точного совпадения и группировки. Keyword хранится целиком
 * и годится ровно для обратного. Город здесь Keyword, потому что по нему фильтруют,
 * а не ищут; название - Text, потому что по нему ищут.
 *
 * <p>Анализатор {@code russian} встроен в Elasticsearch и делает две вещи, ради которых
 * всё это и затевалось: приводит слова к основам и выбрасывает предлоги.
 *
 * <p>{@code indexedAt} нужен для уборки. Переиндексация переписывает документы по их
 * идентификаторам, но ничего не знает об удалённых ресторанах: их документы просто
 * не обновятся. По метке времени такие документы и находятся - у них она осталась
 * от прошлого прохода.
 */
@Document(indexName = RestaurantDocument.INDEX)
public record RestaurantDocument(

        @Id
        String id,

        @Field(type = FieldType.Long)
        Long restaurantId,

        @Field(type = FieldType.Text, analyzer = "russian")
        String name,

        @Field(type = FieldType.Text, analyzer = "russian")
        String description,

        @Field(type = FieldType.Keyword)
        String city,

        @Field(type = FieldType.Boolean)
        boolean active,

        /**
         * Два формата, и второй нужен для запросов. Хранится значение в читаемом
         * date_time — его видно глазами в любом просмотрщике индекса. Но сравнивать
         * с ним в запросе значит воспроизвести формат строкой в точности, вплоть до числа
         * знаков после запятой, а {@code Instant.toString()} их даёт то три, то шесть,
         * то ни одного. Разрешив полю ещё и epoch_millis, мы получаем возможность
         * запрашивать по числу — форму, у которой нет вариантов написания.
         */
        @Field(type = FieldType.Date, format = {DateFormat.date_time, DateFormat.epoch_millis})
        Instant indexedAt
) {

    public static final String INDEX = "food-delivery-restaurants";

    /**
     * Идентификатор документа выводится из идентификатора ресторана, а не генерируется.
     * Это и делает переиндексацию идемпотентной: повторный проход перезаписывает те же
     * документы, а не создаёт вторые копии.
     */
    public static String documentId(Long restaurantId) {
        return String.valueOf(restaurantId);
    }
}
