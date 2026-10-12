package com.dima.fooddelivery.contract;

import com.dima.fooddelivery.client.ApiClient;
import com.dima.fooddelivery.client.api.SearchApi;
import com.dima.fooddelivery.search.api.SearchController;
import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.ReindexResult;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import com.dima.fooddelivery.search.domain.SearchResults;
import com.dima.fooddelivery.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сгенерированный SDK вызывает настоящий контроллер поиска через MockMvc transport:
 * маршруты, параметры и JSON без Elasticsearch. Переехал из модуля {@code client} вместе
 * с поиском — проверять контракт нужно против того сервиса, который его отдаёт.
 */
class SearchClientContractTest {

    private final SearchService search = mock(SearchService.class);
    private ApiClient client;

    @BeforeEach
    void setUp() {
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(search)).build();
        client = new ApiClient(ApiClient.buildRestClientBuilder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc)).build());
    }

    @Test
    void searchPreservesQueryNamesAndReindexCounts() {
        when(search.search("пицца", "Москва", 10)).thenReturn(new SearchResults("пицца",
                List.of(new RestaurantDocument("3", 3L, "Пиццерия", null, "Москва", true, Instant.now())),
                List.of(new MenuItemDocument("4", 4L, 3L, "Пиццерия", "Москва", "Пицца", null,
                        450.25, true, Instant.now()))));
        when(search.reindexAll()).thenReturn(new ReindexResult(1, 1, 2));
        var api = new SearchApi(client);
        var found = api.search("пицца", "Москва", null);
        assertEquals("пицца", found.getQuery());
        assertEquals(new BigDecimal("450.25"), found.getItems().get(0).getPrice());
        assertTrue(found.getRestaurants().get(0).getActive());
        assertEquals(2L, api.reindex().getRemoved());
        verify(search).search("пицца", "Москва", 10);
    }
}
