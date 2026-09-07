package com.dima.fooddelivery.search.persistence;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchAllQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Поисковый индекс: создание, наполнение, уборка и запросы.
 *
 * <p>Работа идёт через {@link ElasticsearchOperations} и типизированный Query DSL клиента
 * Elasticsearch, а не через производные методы репозитория. Причина та же, что у Cypher
 * в модуле рекомендаций: запрос — это и есть суть модуля. Прятать multi_match с нечёткостью
 * за именем метода {@code findByNameContaining} значит спрятать ровно то, ради чего
 * Elasticsearch и поставили.
 *
 * <p><b>Как устроен поиск.</b> multi_match ищет по нескольким полям сразу и берёт лучшее
 * совпадение. Вес {@code ^3} у названия поднимает совпадение в названии над совпадением
 * в описании: блюдо, которое называется «пицца», должно стоять выше блюда, у которого
 * слово «пицца» встретилось в описании. Нечёткость AUTO разрешает одну-две опечатки
 * в зависимости от длины слова — короткие слова не искажаются, иначе «сок» находил бы «сон».
 *
 * <p>Город фильтром, а не частью поиска: фильтр не участвует в подсчёте релевантности
 * и кэшируется, тогда как условие в must пересчитывало бы вес каждого документа.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.ELASTICSEARCH, havingValue = "true")
public class SearchIndex {

    private final ElasticsearchOperations operations;

    /**
     * Создаёт индексы с маппингом, если их ещё нет.
     *
     * <p>Автоматическое создание индексов Spring Data Elasticsearch по умолчанию выключено,
     * и это правильно: индекс, созданный сам по первому документу, получает угаданный
     * маппинг. Угадывает Elasticsearch разумно, но анализатор {@code russian} он угадать
     * не может, и поиск по морфологии молча перестал бы работать.
     */
    @PostConstruct
    void createIndexes() {
        createIfMissing(RestaurantDocument.class, RestaurantDocument.INDEX);
        createIfMissing(MenuItemDocument.class, MenuItemDocument.INDEX);
    }

    public void indexRestaurants(List<RestaurantDocument> documents) {
        if (!documents.isEmpty()) {
            // save на коллекции уходит в bulk API одним запросом. Сохранение по одному
            // документу означало бы отдельный HTTP-запрос на каждый ресторан.
            operations.save(documents);
        }
    }

    public void indexMenuItems(List<MenuItemDocument> documents) {
        if (!documents.isEmpty()) {
            operations.save(documents);
        }
    }

    /**
     * Удаляет документы, которых не коснулась последняя переиндексация.
     *
     * <p>Это и есть уборка за удалёнными ресторанами и блюдами. Переиндексация переписывает
     * существующие документы по их идентификаторам и о пропавших записях узнать не может:
     * их просто нет во входных данных. Метка времени превращает эту невидимую разницу
     * в обычный запрос — всё, что старше начала прохода, к текущему состоянию отношения
     * не имеет.
     */
    public long removeStale(Instant indexedBefore) {
        long removed = deleteOlderThan(RestaurantDocument.class, indexedBefore)
                + deleteOlderThan(MenuItemDocument.class, indexedBefore);

        if (removed > 0) {
            log.info("Из поискового индекса убрано устаревших документов: {}", removed);
        }

        return removed;
    }

    public List<RestaurantDocument> searchRestaurants(String text, String city, int size) {
        return search(
                RestaurantDocument.class,
                textQuery(text, city, "name^3", "description", "city"),
                size
        );
    }

    public List<MenuItemDocument> searchMenuItems(String text, String city, int size) {
        return search(
                MenuItemDocument.class,
                textQuery(text, city, "name^3", "description", "restaurantName"),
                size
        );
    }

    /**
     * Принудительное обновление индексов.
     *
     * <p>Elasticsearch делает записанный документ видимым для поиска не сразу, а с задержкой
     * до секунды: это плата за скорость записи. В обычной работе задержка незаметна, а вот
     * тест, который проиндексировал и тут же ищет, без этого вызова находил бы пустоту.
     */
    public void refresh() {
        operations.indexOps(RestaurantDocument.class).refresh();
        operations.indexOps(MenuItemDocument.class).refresh();
    }

    private void createIfMissing(Class<?> documentType, String indexName) {
        IndexOperations indexOps = operations.indexOps(documentType);

        if (!indexOps.exists()) {
            indexOps.createWithMapping();

            log.info("Создан индекс Elasticsearch: {}", indexName);
        }
    }

    /**
     * Граница сравнения передаётся числом миллисекунд, а не строкой с датой.
     *
     * <p>Поле объявлено в двух форматах именно ради этого. Строковое представление момента
     * времени в Java зависит от того, сколько знаков после запятой оказалось ненулевыми,
     * а Elasticsearch разбирает такую строку строго по формату поля: три знака — разберёт,
     * шесть или ноль — не разберёт. Число миллисекунд однозначно всегда.
     */
    private long deleteOlderThan(Class<?> documentType, Instant indexedBefore) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(RangeQuery.of(range -> range
                        .date(date -> date
                                .field("indexedAt")
                                .lt(String.valueOf(indexedBefore.toEpochMilli()))
                        )
                )._toQuery())
                .build();

        return operations.delete(DeleteQuery.builder(query).build(), documentType).getDeleted();
    }

    private <T> List<T> search(Class<T> documentType, Query query, int size) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withMaxResults(size)
                .build();

        return operations.search(nativeQuery, documentType).getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();
    }

    /**
     * Собирает запрос: поиск по тексту плюс необязательный фильтр по городу.
     *
     * <p>Пустой текст — не ошибка и не повод вернуть пустоту. Это запрос вида «покажи всё,
     * что есть в Москве», и он честно превращается в match_all с фильтром.
     */
    private static Query textQuery(String text, String city, String... fields) {
        Query byText = text == null || text.isBlank()
                ? MatchAllQuery.of(all -> all)._toQuery()
                : MultiMatchQuery.of(match -> match
                        .query(text)
                        .fields(List.of(fields))
                        .fuzziness("AUTO")
                )._toQuery();

        if (city == null || city.isBlank()) {
            return byText;
        }

        return BoolQuery.of(bool -> bool
                .must(byText)
                .filter(TermQuery.of(term -> term
                        .field("city")
                        .value(city)
                )._toQuery())
        )._toQuery();
    }

}
