package com.dima.fooddelivery.search.service;

import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.ReindexResult;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import com.dima.fooddelivery.search.domain.SearchResults;
import com.dima.fooddelivery.search.persistence.SearchIndex;
import com.dima.fooddelivery.support.AbstractStoresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поиск на настоящем Elasticsearch.
 *
 * <p>Проверяется ровно то, ради чего он поставлен: морфология и опечатки. Оба свойства дают
 * анализатор и нечёткое совпадение, то есть настройка индекса и форма запроса, — код,
 * который на моках выглядит одинаково правильным при любом маппинге.
 *
 * <p>Каждый тест переиндексирует витрину целиком и делает refresh: Elasticsearch показывает
 * записанный документ поиску не сразу, и без явного обновления тест искал бы в пустоте.
 */
class SearchIT extends AbstractStoresIntegrationTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private SearchIndex searchIndex;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldFindRestaurantByWordFromItsName() {
        long suffix = testData.uniqueSuffix();
        String marker = "мультипиццерия" + suffix;

        insertRestaurant(marker + " на Тверской", "Пекарня и пиццерия", "Москва");

        reindex();

        SearchResults results = searchService.search(marker, null, 10);

        assertAll(
                () -> assertEquals(1, results.restaurants().size()),
                () -> assertTrue(results.restaurants().get(0).name().startsWith(marker))
        );
    }

    /**
     * Опечатка в запросе. Ради этого и берут Elasticsearch: LIKE в PostgreSQL здесь
     * не нашёл бы ничего, а полнотекстовый поиск по tsvector — тоже ничего, потому что
     * знает про словоформы, но не про ошибки набора.
     */
    @Test
    void shouldFindDishDespiteTypoInQuery() {
        long suffix = testData.uniqueSuffix();
        Long restaurantId = insertRestaurant("Пиццерия " + suffix, "Тестовый ресторан", "Москва");

        insertMenuItem(restaurantId, "Пепперони" + suffix, "Острая пицца с колбасками");

        reindex();

        List<MenuItemDocument> found = searchIndex.searchMenuItems("пеперони" + suffix, null, 10);

        assertEquals(1, found.size(), "одна пропущенная буква не должна ломать поиск");
    }

    /** Город — фильтр, а не часть текстового запроса: он либо совпадает точно, либо нет. */
    @Test
    void shouldFilterByCity() {
        long suffix = testData.uniqueSuffix();
        String marker = "шаурмичная" + suffix;

        insertRestaurant(marker + " центральная", "Тестовый ресторан", "Москва");
        insertRestaurant(marker + " приморская", "Тестовый ресторан", "Владивосток");

        reindex();

        SearchResults inMoscow = searchService.search(marker, "Москва", 10);
        SearchResults everywhere = searchService.search(marker, null, 10);

        assertAll(
                () -> assertEquals(1, inMoscow.restaurants().size()),
                () -> assertEquals("Москва", inMoscow.restaurants().get(0).city()),
                () -> assertEquals(2, everywhere.restaurants().size())
        );
    }

    /**
     * Блюдо ищется и по названию своего ресторана: оно скопировано в документ блюда,
     * и это единственная причина, по которой поиск обходится одним обращением.
     */
    @Test
    void shouldFindDishByRestaurantName() {
        long suffix = testData.uniqueSuffix();
        String marker = "вегетарианская" + suffix;

        Long restaurantId = insertRestaurant(marker + " кухня", "Тестовый ресторан", "Москва");
        insertMenuItem(restaurantId, "Салат " + suffix, "Свежие овощи");

        reindex();

        List<MenuItemDocument> found = searchIndex.searchMenuItems(marker, null, 10);

        assertEquals(1, found.size());
    }

    /**
     * Уборка индекса. Ресторан, исчезнувший из витрины, не участвует в следующей
     * переиндексации, и его документ должен пропасть по метке времени — иначе поиск ещё
     * долго предлагал бы закрытые заведения.
     */
    @Test
    void shouldRemoveDocumentsOfDeletedRestaurants() {
        long suffix = testData.uniqueSuffix();
        String marker = "временное" + suffix;

        Long restaurantId = insertRestaurant(marker + " заведение", "Тестовый ресторан", "Москва");

        reindex();
        assertEquals(1, searchIndex.searchRestaurants(marker, null, 10).size(), "документ должен появиться");

        jdbcTemplate.update("DELETE FROM restaurant WHERE id = ?", restaurantId);

        reindex();

        List<RestaurantDocument> afterDeletion = searchIndex.searchRestaurants(marker, null, 10);

        assertTrue(afterDeletion.isEmpty(), "документ удалённого ресторана обязан исчезнуть из индекса");
    }

    @Test
    void shouldReportWhatWasIndexed() {
        insertRestaurant("Ресторан " + testData.uniqueSuffix(), "Тестовый ресторан", "Москва");

        ReindexResult result = reindex();

        assertTrue(result.restaurants() > 0, "в витрине есть рестораны из миграций и из теста");
    }

    private ReindexResult reindex() {
        ReindexResult result = searchService.reindexAll();

        searchIndex.refresh();

        return result;
    }

    /**
     * Ресторан с заданным названием: {@code TestDataFactory} называет их одинаково, а поиску
     * нужны различимые слова.
     */
    private Long insertRestaurant(String name, String description, String city) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO restaurant (name, description, city, is_active)
                        VALUES (?, ?, ?, TRUE)
                        RETURNING id
                        """,
                Long.class,
                name,
                description,
                city
        );
    }

    private void insertMenuItem(Long restaurantId, String name, String description) {
        Long categoryId = testData.insertMenuCategory(restaurantId);

        jdbcTemplate.update(
                """
                        INSERT INTO menu_item (category_id, name, description, price, is_available, sort_order)
                        VALUES (?, ?, ?, ?, TRUE, 1)
                        """,
                categoryId,
                name,
                description,
                new BigDecimal("450.00")
        );
    }
}
