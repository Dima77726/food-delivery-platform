package com.dima.fooddelivery.search.service;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.stores.ElasticsearchStoreProperties;
import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.menu.domain.MenuCategoryView;
import com.dima.fooddelivery.menu.domain.MenuItemView;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.menu.service.MenuService;
import com.dima.fooddelivery.restaurant.domain.Restaurant;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.ReindexResult;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import com.dima.fooddelivery.search.domain.SearchResults;
import com.dima.fooddelivery.search.persistence.SearchIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Модуль Search. Поиск по витрине и наполнение индекса.
 *
 * <p><b>Почему индекс наполняется переиндексацией, а не записью при каждом изменении.</b>
 * Двойная запись (сохранили ресторан в PostgreSQL и тут же документ в Elasticsearch) выглядит
 * проще и точнее, но у неё есть свойство, которое всё портит: две записи в разные хранилища
 * нельзя сделать атомарно. Упавшая вторая запись оставляет индекс расходящимся с базой
 * навсегда, и заметить это можно только по жалобе.
 *
 * <p>Переиндексация даёт другое свойство: индекс — функция от текущего состояния PostgreSQL,
 * и любое расхождение исправляется само на следующем проходе. Плата — задержка: изменение
 * меню становится видно поиску не мгновенно, а через интервал. Для витрины ресторанов это
 * приемлемо; для чего-то вроде остатков на складе — нет, и там пришлось бы городить
 * transactional outbox, как это сделано для событий заказа.
 *
 * <p><b>Данные берутся у соседних модулей, а не из их таблиц.</b> Рестораны спрашиваются
 * у {@link RestaurantService}, меню — у {@link MenuService}. Правило то же, что и везде
 * в проекте.
 *
 * <p>Список ресторанов при этом читается мимо кэша, а меню — через кэш, и разница
 * не случайна. Список задаёт, что вообще попадёт в индекс: устаревший, он уводит проход
 * за меню несуществующего ресторана. Устаревшее меню в худшем случае даёт в поиске цену
 * минутной давности, а следующий проход её поправит.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.ELASTICSEARCH, havingValue = "true")
public class SearchService {

    private final SearchIndex searchIndex;
    private final RestaurantService restaurantService;
    private final MenuService menuService;
    private final ElasticsearchStoreProperties properties;

    /**
     * Поиск по ресторанам и блюдам одновременно.
     *
     * @param text текст запроса; пусто означает «показать всё»
     * @param city необязательный фильтр по городу
     * @param size сколько результатов вернуть в каждом из двух списков
     */
    public SearchResults search(String text, String city, int size) {
        int limited = Math.min(size, properties.maxResults());

        return new SearchResults(
                text,
                searchIndex.searchRestaurants(text, city, limited),
                searchIndex.searchMenuItems(text, city, limited)
        );
    }

    /**
     * Полная переиндексация витрины.
     *
     * <p>Момент начала прохода фиксируется до чтения данных и проставляется всем документам.
     * После записи всё, что старше этого момента, удаляется как не подтверждённое текущим
     * состоянием. Взять время после прохода было бы ошибкой: документы, записанные в начале,
     * оказались бы старше метки и удалились бы сразу после записи.
     */
    public ReindexResult reindexAll() {
        Instant startedAt = Instant.now();

        List<Restaurant> restaurants = restaurantService.findAllForProjection();

        List<RestaurantDocument> restaurantDocuments = new ArrayList<>(restaurants.size());
        List<MenuItemDocument> itemDocuments = new ArrayList<>();

        for (Restaurant restaurant : restaurants) {
            restaurantDocuments.add(new RestaurantDocument(
                    RestaurantDocument.documentId(restaurant.getId()),
                    restaurant.getId(),
                    restaurant.getName(),
                    restaurant.getDescription(),
                    restaurant.getCity(),
                    restaurant.isActive(),
                    startedAt
            ));

            itemDocuments.addAll(menuDocuments(restaurant, startedAt));
        }

        searchIndex.indexRestaurants(restaurantDocuments);
        searchIndex.indexMenuItems(itemDocuments);

        long removed = searchIndex.removeStale(startedAt);

        log.info(
                "Переиндексация завершена: ресторанов={}, блюд={}, удалено устаревших={}",
                restaurantDocuments.size(),
                itemDocuments.size(),
                removed
        );

        return new ReindexResult(restaurantDocuments.size(), itemDocuments.size(), removed);
    }

    /**
     * Документы блюд одного ресторана.
     *
     * <p>Берётся публичное меню, а не меню владельца: архивные позиции в поиске не нужны,
     * их нельзя заказать. Категории при этом в индекс не попадают вовсе — ищут блюдо,
     * а не раздел меню.
     *
     * <p>Исчезнувший ресторан — не повод прерывать проход. Между чтением списка и чтением
     * его меню проходит время, и за это время ресторан могут удалить; полный проход
     * по витрине делает такую гонку не редкостью, а вопросом времени. Пропущенный ресторан
     * просто не попадёт в индекс, а его старые документы уберёт та же уборка по метке
     * времени, что и для удалённых.
     */
    private List<MenuItemDocument> menuDocuments(Restaurant restaurant, Instant indexedAt) {
        RestaurantMenu menu;

        try {
            menu = menuService.getRestaurantMenu(restaurant.getId());
        } catch (ResourceNotFoundException disappeared) {
            log.info("Ресторан пропал во время переиндексации, пропускаем: restaurantId={}", restaurant.getId());

            return List.of();
        }

        List<MenuItemDocument> documents = new ArrayList<>();

        for (MenuCategoryView category : menu.categories()) {
            for (MenuItemView item : category.items()) {
                documents.add(new MenuItemDocument(
                        MenuItemDocument.documentId(item.id()),
                        item.id(),
                        restaurant.getId(),
                        restaurant.getName(),
                        restaurant.getCity(),
                        item.name(),
                        item.description(),
                        item.price().doubleValue(),
                        item.available(),
                        indexedAt
                ));
            }
        }

        return documents;
    }
}
