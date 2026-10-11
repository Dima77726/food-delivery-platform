package com.dima.fooddelivery.graphql;

import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.service.AnalyticsService;
import com.dima.fooddelivery.catalog.CatalogClient;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.common.security.ResourceAccessGuard;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import com.dima.fooddelivery.recommendation.service.RecommendationService;
import com.dima.fooddelivery.review.api.ReviewResponse;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Схема и резолверы GraphQL без серверов: сервисы модулей подменены, проверяется то,
 * за что отвечает именно этот слой, — что поля склеиваются из разных источников, что
 * незапрошенное не вычисляется, что права проверяются на поле, а ошибка поля не роняет
 * весь ответ. Сами запросы к MongoDB, Neo4j и ClickHouse проверяют тесты модулей.
 */
@GraphQlTest(InsightsGraphqlController.class)
@Import(GraphqlConfig.class)
// Предел глубины ниже боевого: у схемы самый глубокий путь — пять уровней, и превысить
// боевые шесть ей пока нечем. Здесь проверяется сам механизм отказа.
@TestPropertySource(properties = "app.graphql.max-query-depth=4")
class InsightsGraphqlControllerTest {

    private static final long RESTAURANT_ID = 3L;

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private CatalogClient catalog;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private ResourceAccessGuard access;

    @MockitoBean
    private ReviewService reviews;

    @MockitoBean
    private RecommendationService recommendations;

    @MockitoBean
    private AnalyticsService analytics;

    @BeforeEach
    void restaurantExists() {
        when(catalog.findRestaurant(RESTAURANT_ID))
                .thenReturn(Optional.of(new CatalogClient.RestaurantInfo(RESTAURANT_ID, "Пиццерия", "Москва", true)));
        when(currentUser.id()).thenReturn(Optional.empty());
    }

    /** Экран ресторана одним запросом: каталог, MongoDB и граф — и ни одного лишнего похода. */
    @Test
    void shouldAssembleRestaurantScreenFromSeveralSources() {
        when(reviews.getSummary(RESTAURANT_ID)).thenReturn(new RatingSummary(
                RESTAURANT_ID, 2, 4.5, Map.of(5, 1L, 4, 1L), List.of(new RatingSummary.TagCount("вкусно", 2))));
        when(reviews.getRestaurantReviews(any(), any())).thenReturn(PageResponse.of(List.of(new ReviewResponse(
                "r-1", 10L, RESTAURANT_ID, 7L, 5, "Отлично",
                List.of(new ReviewResponse.DishRatingResponse(100L, "Маргарита", 5)),
                List.of("вкусно"), OffsetDateTime.parse("2026-10-12T12:00:00Z"))), 0, 10, 1));
        when(recommendations.popularInRestaurant(RESTAURANT_ID, 3))
                .thenReturn(List.of(new RecommendedItem(100L, "Маргарита", RESTAURANT_ID, 12)));

        graphQlTester.document("""
                        {
                          restaurant(id: 3) {
                            name
                            city
                            rating { average total histogram { stars count } topTags { tag } }
                            reviews(size: 10) { totalElements items { rating comment } }
                            popularDishes(limit: 3) { name score }
                          }
                        }
                        """)
                .execute()
                .errors().verify()
                .path("restaurant.name").entity(String.class).isEqualTo("Пиццерия")
                .path("restaurant.rating.average").entity(Double.class).isEqualTo(4.5)
                .path("restaurant.rating.histogram[0].stars").entity(Integer.class).isEqualTo(5)
                .path("restaurant.reviews.items[0].comment").entity(String.class).isEqualTo("Отлично")
                .path("restaurant.popularDishes[0].score").entity(Integer.class).isEqualTo(12);

        // Продажи не запрошены — ClickHouse не трогали.
        verifyNoInteractions(analytics);
    }

    @Test
    void shouldReturnNullForUnknownRestaurant() {
        when(catalog.findRestaurant(404L)).thenReturn(Optional.empty());

        graphQlTester.document("{ restaurant(id: 404) { name } }")
                .execute()
                .path("restaurant").valueIsNull();
    }

    /** Закрытое поле без токена — ошибка этого поля, остальные приходят как обычно. */
    @Test
    void shouldRejectSalesForAnonymousButKeepPublicFields() {
        graphQlTester.document("""
                        { restaurant(id: 3) { name sales(from: "2026-10-01", to: "2026-10-12") { totalOrders } } }
                        """)
                .execute()
                .errors().expect(error -> error.getErrorType() == ErrorType.UNAUTHORIZED).verify()
                .path("restaurant.name").entity(String.class).isEqualTo("Пиццерия");

        verifyNoInteractions(analytics);
    }

    @Test
    void shouldRejectSalesForUserWhoDoesNotManageRestaurant() {
        when(currentUser.id()).thenReturn(Optional.of(7L));
        when(access.managesRestaurant(RESTAURANT_ID)).thenReturn(false);

        graphQlTester.document("""
                        { restaurant(id: 3) { sales(from: "2026-10-01", to: "2026-10-12") { totalOrders } } }
                        """)
                .execute()
                .errors().expect(error -> error.getErrorType() == ErrorType.FORBIDDEN).verify();
    }

    @Test
    void shouldShowSalesToManager() {
        when(currentUser.id()).thenReturn(Optional.of(7L));
        when(access.managesRestaurant(RESTAURANT_ID)).thenReturn(true);
        LocalDate day = LocalDate.parse("2026-10-12");
        when(analytics.dailySales(RESTAURANT_ID, LocalDate.parse("2026-10-01"), day)).thenReturn(List.of(
                new DailySales(day, 2, new BigDecimal("900.00"), new BigDecimal("450.00"))));

        graphQlTester.document("""
                        { restaurant(id: 3) { sales(from: "2026-10-01", to: "2026-10-12") { totalOrders totalRevenue days { day } } } }
                        """)
                .execute()
                .errors().verify()
                .path("restaurant.sales.totalOrders").entity(Integer.class).isEqualTo(2)
                .path("restaurant.sales.totalRevenue").entity(String.class).isEqualTo("900.00")
                .path("restaurant.sales.days[0].day").entity(String.class).isEqualTo("2026-10-12");
    }

    @Test
    void shouldRequireTokenForOwnRecommendations() {
        graphQlTester.document("{ myRecommendations { name } }")
                .execute()
                .errors().expect(error -> error.getErrorType() == ErrorType.UNAUTHORIZED).verify();
    }

    @Test
    void shouldFollowDishGraph() {
        when(recommendations.orderedTogetherWith(anyLong(), anyInt()))
                .thenReturn(List.of(new RecommendedItem(101L, "Кола", RESTAURANT_ID, 4)));

        graphQlTester.document("{ dish(id: 100) { orderedTogether(limit: 2) { name } } }")
                .execute()
                .errors().verify()
                .path("dish.orderedTogether[0].name").entity(String.class).isEqualTo("Кола");
    }

    /** Слишком глубокий запрос отклоняется до выполнения — ни один резолвер не вызывается. */
    @Test
    void shouldRejectTooDeepQuery() {
        graphQlTester.document("""
                        { restaurant(id: 3) { reviews { items { dishes { menuItemName } } } } }
                        """)
                .execute()
                .errors().expect(error -> error.getMessage().toLowerCase().contains("depth")).verify();

        verifyNoInteractions(catalog, reviews);
    }
}
