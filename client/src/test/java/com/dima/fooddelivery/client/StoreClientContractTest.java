package com.dima.fooddelivery.client;

import com.dima.fooddelivery.analytics.api.AnalyticsController;
import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.domain.StatusCount;
import com.dima.fooddelivery.analytics.service.AnalyticsService;
import com.dima.fooddelivery.client.api.AnalyticsApi;
import com.dima.fooddelivery.client.api.RecommendationApi;
import com.dima.fooddelivery.client.api.ReviewApi;
import com.dima.fooddelivery.client.api.SearchApi;
import com.dima.fooddelivery.client.api.TrackingApi;
import com.dima.fooddelivery.client.model.CreateReviewRequest;
import com.dima.fooddelivery.client.model.DishRatingRequest;
import com.dima.fooddelivery.client.model.ReportPositionRequest;
import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.recommendation.api.RecommendationController;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import com.dima.fooddelivery.recommendation.service.RecommendationService;
import com.dima.fooddelivery.review.api.ReviewController;
import com.dima.fooddelivery.review.api.ReviewResponse;
import com.dima.fooddelivery.review.domain.DishRating;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.service.ReviewService;
import com.dima.fooddelivery.search.api.SearchController;
import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.ReindexResult;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import com.dima.fooddelivery.search.domain.SearchResults;
import com.dima.fooddelivery.search.service.SearchService;
import com.dima.fooddelivery.tracking.api.TrackingController;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import com.dima.fooddelivery.tracking.domain.CourierTrack;
import com.dima.fooddelivery.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Сгенерированный SDK вызывает настоящие MVC-контроллеры через MockMvc transport.
 * Проверяет маршруты, параметры, HTTP-коды и JSON всех 15 операций без баз данных.
 * Бизнес-правила и авторизацию проверяют интеграционные тесты сервиса.
 */
class StoreClientContractTest {
    private final ReviewService reviews = mock(ReviewService.class);
    private final TrackingService tracking = mock(TrackingService.class);
    private final RecommendationService recommendations = mock(RecommendationService.class);
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final SearchService search = mock(SearchService.class);
    private ApiClient client;

    @BeforeEach
    void setUp() {
        var mvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviews),
                new TrackingController(tracking), new RecommendationController(recommendations),
                new AnalyticsController(analytics), new SearchController(search)).build();
        client = new ApiClient(ApiClient.buildRestClientBuilder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc)).build());
    }

    @Test
    void nullDishIsRejectedAsBadRequestBeforeCallingTheService() {
        var request = new CreateReviewRequest().rating(5).dishes(Collections.singletonList(null));
        assertThrows(HttpClientErrorException.BadRequest.class,
                () -> new ReviewApi(client).createReview(1L, 2L, request));
        verifyNoInteractions(reviews);
    }

    @Test
    void reviewRequestsAndNestedResponsesMatchTheServer() {
        var timestamp = OffsetDateTime.parse("2026-09-09T12:00:00Z");
        var response = new ReviewResponse("review-1", 2L, 3L, 1L, 5, null,
                List.of(new ReviewResponse.DishRatingResponse(4L, "Пицца", 5)), List.of("вкусно"), timestamp);
        when(reviews.createReview(eq(1L), eq(2L), eq(5), isNull(), anyList(), anyList())).thenReturn(response);
        when(reviews.getRestaurantReviews(eq(3L), any())).thenReturn(PageResponse.of(List.of(response), 0, 20, 1));
        when(reviews.getSummary(3L)).thenReturn(new RatingSummary(3L, 1, 5.0, Map.of(5, 1L),
                List.of(new RatingSummary.TagCount("вкусно", 1))));
        var api = new ReviewApi(client);
        var created = api.createReviewWithHttpInfo(1L, 2L, new CreateReviewRequest().rating(5)
                .dishes(List.of(new DishRatingRequest().menuItemId(4L).menuItemName("Пицца").rating(5)))
                .tags(List.of("вкусно")));
        assertEquals(201, created.getStatusCode().value());
        assertEquals(timestamp, created.getBody().getCreatedAt());
        assertEquals("Пицца", created.getBody().getDishes().get(0).getMenuItemName());
        assertEquals(1L, api.getRestaurantReviews(3L, null, null).getTotalElements());
        assertEquals(1L, api.getRestaurantRatingSummary(3L).getHistogram().get("5"));
        verify(reviews).getRestaurantReviews(3L, new PageRequestParams(0, 20));
        verify(reviews).createReview(1L, 2L, 5, null, List.of(new DishRating(4L, "Пицца", 5)), List.of("вкусно"));
    }

    @Test
    void trackingPreservesAcceptedStatusAndMissingSpeed() {
        var point = new CourierPosition(2L, 1L, Instant.parse("2026-09-09T12:00:00Z"), 55.75, 37.61, null);
        when(tracking.reportPosition(1L, 2L, 55.75, 37.61, null)).thenReturn(point);
        when(tracking.getTrackForCourier(1L, 2L, 100)).thenReturn(new CourierTrack(2L, List.of(point)));
        when(tracking.getTrackForCustomerOrder(3L, 4L, 7)).thenReturn(new CourierTrack(2L, List.of(point)));
        var api = new TrackingApi(client);
        var accepted = api.reportPositionWithHttpInfo(1L, 2L,
                new ReportPositionRequest().latitude(55.75).longitude(37.61));
        assertEquals(202, accepted.getStatusCode().value());
        assertNull(accepted.getBody().getSpeedKmh());
        assertEquals(1, api.getCourierTrack(1L, 2L, null).getSize());
        assertEquals(2L, api.getOrderTrack(3L, 4L, 7).getDeliveryId());
        verify(tracking).getTrackForCourier(1L, 2L, 100);
    }

    @Test
    void recommendationRoutesAndLimitsMatch() {
        var items = List.of(new RecommendedItem(2L, "Пицца", 3L, 4L));
        when(recommendations.recommendForCustomer(1L, 10)).thenReturn(items);
        when(recommendations.orderedTogetherWith(2L, 5)).thenReturn(items);
        when(recommendations.projectNextBatch()).thenReturn(200);
        when(recommendations.rebuild()).thenReturn(300);
        var api = new RecommendationApi(client);
        assertEquals(4L, api.getRecommendations(1L, null).get(0).getScore());
        assertEquals(2L, api.getOrderedTogether(2L, 5).get(0).getMenuItemId());
        assertEquals(200, api.projectNow().getOrdersRead());
        assertEquals(300, api.rebuildRecommendations().getOrdersRead());
    }

    @Test
    void analyticsPreservesMoneyDatesAndPlatformScope() {
        var day = LocalDate.of(2026, 9, 9);
        var amount = new BigDecimal("123456789.12");
        var sales = List.of(new DailySales(day, 1L, amount, amount));
        when(analytics.dailySales(3L, day, day)).thenReturn(sales);
        when(analytics.dailySales(null, day, day)).thenReturn(sales);
        when(analytics.statusFunnel(3L, day, day)).thenReturn(List.of(new StatusCount("PAID", 1L)));
        var api = new AnalyticsApi(client);
        assertEquals(amount, api.getRestaurantSales(3L, day, day).getTotalRevenue());
        var platform = api.getPlatformSales(day, day);
        assertNull(platform.getRestaurantId());
        assertEquals(day, platform.getFrom());
        assertEquals(amount, platform.getDays().get(0).getAverageCheck());
        assertEquals("PAID", api.getRestaurantFunnel(3L, day, day).getStatuses().get(0).getStatus());
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
