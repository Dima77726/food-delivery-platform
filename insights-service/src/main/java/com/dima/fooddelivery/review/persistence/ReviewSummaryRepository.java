package com.dima.fooddelivery.review.persistence;

import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.domain.Review;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сводка по отзывам ресторана — одним aggregation pipeline.
 *
 * <p>Это вторая половина работы с MongoDB, которую производные запросы репозитория
 * не покрывают: посчитать среднее, разложить оценки по корзинам и найти самые частые метки
 * именем метода нельзя. Здесь начинается {@link MongoTemplate} и конвейер стадий.
 *
 * <p><b>Ключевая стадия — facet.</b> Нужны три независимых ответа по одной и той же выборке,
 * и facet считает их за один проход и одно обращение к базе. Три отдельные агрегации дали бы
 * три обращения, а главное — три разных момента времени: между первым и третьим запросом мог
 * бы появиться новый отзыв, и общее число перестало бы сходиться с суммой по гистограмме.
 *
 * <p>Результат разбирается как {@link Document}, а не мапится в типизированный класс.
 * Причина в форме ответа facet: это документ, у которого каждое поле — массив результатов
 * своей подветки, и промежуточных типов под него пришлось бы объявить три штуки, причём
 * использовались бы они ровно один раз, вот в этом методе.
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.MONGO, havingValue = "true")
public class ReviewSummaryRepository {

    /** Сколько меток показывать в сводке. Больше пяти читателю уже не помогают. */
    private static final int TOP_TAGS_LIMIT = 5;

    private final MongoTemplate mongoTemplate;

    public RatingSummary summarize(Long restaurantId) {
        Aggregation aggregation = Aggregation.newAggregation(
                // Сначала фильтр, и только потом всё остальное. Порядок стадий в конвейере —
                // это порядок выполнения: match первым позволяет MongoDB воспользоваться
                // индексом по restaurantId и дальше работать с сотней документов вместо всех.
                Aggregation.match(Criteria.where("restaurantId").is(restaurantId)),

                Aggregation.facet()
                        // Общее: сколько всего и какое среднее.
                        .and(
                                Aggregation.group().count().as("total").avg("rating").as("average")
                        ).as("overall")

                        // Гистограмма: сколько отзывов на каждую оценку.
                        .and(
                                Aggregation.group("rating").count().as("count"),
                                Aggregation.sort(Sort.Direction.DESC, "_id")
                        ).as("histogram")

                        // Топ меток: развернуть массив tags в отдельные документы,
                        // сгруппировать и взять первые пять.
                        .and(
                                Aggregation.unwind("tags"),
                                Aggregation.group("tags").count().as("count"),
                                Aggregation.sort(Sort.Direction.DESC, "count"),
                                Aggregation.limit(TOP_TAGS_LIMIT)
                        ).as("topTags")
        );

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, Review.COLLECTION, Document.class);

        Document facets = results.getUniqueMappedResult();

        if (facets == null) {
            return RatingSummary.empty(restaurantId);
        }

        return new RatingSummary(
                restaurantId,
                total(facets),
                average(facets),
                histogram(facets),
                topTags(facets)
        );
    }

    private static long total(Document facets) {
        Document overall = first(facets, "overall");

        // Пустая подветка — не ошибка: у ресторана просто нет отзывов. Группировка по пустому
        // входу не порождает ни одного документа, поэтому массив приходит пустым.
        return overall == null ? 0L : ((Number) overall.get("total")).longValue();
    }

    private static double average(Document facets) {
        Document overall = first(facets, "overall");

        return overall == null ? 0.0 : ((Number) overall.get("average")).doubleValue();
    }

    private static Map<Integer, Long> histogram(Document facets) {
        Map<Integer, Long> histogram = new LinkedHashMap<>();

        for (Document bucket : buckets(facets, "histogram")) {
            // _id — то, по чему группировали, то есть сама оценка.
            histogram.put(
                    ((Number) bucket.get("_id")).intValue(),
                    ((Number) bucket.get("count")).longValue()
            );
        }

        return histogram;
    }

    private static List<RatingSummary.TagCount> topTags(Document facets) {
        List<RatingSummary.TagCount> tags = new ArrayList<>();

        for (Document bucket : buckets(facets, "topTags")) {
            tags.add(new RatingSummary.TagCount(
                    String.valueOf(bucket.get("_id")),
                    ((Number) bucket.get("count")).longValue()
            ));
        }

        return tags;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> buckets(Document facets, String name) {
        List<Document> buckets = (List<Document>) facets.get(name);

        return buckets == null ? List.of() : buckets;
    }

    private static Document first(Document facets, String name) {
        List<Document> buckets = buckets(facets, name);

        return buckets.isEmpty() ? null : buckets.get(0);
    }
}
