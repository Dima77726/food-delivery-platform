package com.dima.fooddelivery.graphql;

import com.dima.fooddelivery.analytics.domain.DailySales;
import com.dima.fooddelivery.analytics.service.AnalyticsService;
import com.dima.fooddelivery.catalog.CatalogClient;
import com.dima.fooddelivery.catalog.CatalogClient.RestaurantInfo;
import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.common.security.ResourceAccessGuard;
import com.dima.fooddelivery.recommendation.domain.RecommendedItem;
import com.dima.fooddelivery.recommendation.service.RecommendationService;
import com.dima.fooddelivery.review.api.ReviewResponse;
import com.dima.fooddelivery.review.domain.RatingSummary;
import com.dima.fooddelivery.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GraphQL-вход insights-service.
 *
 * <p>Каждое поле схемы — отдельный обработчик, и вызывается он, только если поле запросили.
 * Поэтому запрос «название и рейтинг» не трогает граф, а «отзывы» не ходит в ClickHouse:
 * клиент платит ровно за то, что берёт. REST так не умеет — у него ответ фиксированной формы.
 *
 * <p>Логика — в тех же сервисах, что обслуживают REST. Здесь только сборка ответа из них
 * и права на поля: рейтинг и отзывы публичны, продажи — только управляющему ресторана.
 *
 * <p>Модули выключаемы (app.stores.*.enabled), поэтому сервисы берутся через
 * {@link ObjectProvider}: выключенный модуль даёт ошибку на своём поле, а не падение всего
 * запроса — остальные поля ответа приходят как обычно.
 */
@Controller
@RequiredArgsConstructor
public class InsightsGraphqlController {

    private static final int MAX_LIMIT = 50;

    private final CatalogClient catalog;
    private final CurrentUser currentUser;
    private final ResourceAccessGuard access;
    private final ObjectProvider<ReviewService> reviews;
    private final ObjectProvider<RecommendationService> recommendations;
    private final ObjectProvider<AnalyticsService> analytics;

    @QueryMapping
    public RestaurantInfo restaurant(@Argument Long id) {
        return catalog.findRestaurant(id).orElse(null);
    }

    @QueryMapping
    public DishRef dish(@Argument Long id) {
        return new DishRef(id);
    }

    @QueryMapping
    public List<RecommendedItem> myRecommendations(@Argument int limit) {
        Long customerId = currentUser.id()
                .orElseThrow(() -> new NotAuthenticatedException("Рекомендации видит только вошедший пользователь"));

        return require(recommendations, "рекомендаций").recommendForCustomer(customerId, clamp(limit));
    }

    @SchemaMapping(typeName = "Restaurant")
    public RatingView rating(RestaurantInfo restaurant) {
        return RatingView.of(require(reviews, "отзывов").getSummary(restaurant.id()));
    }

    @SchemaMapping(typeName = "Restaurant")
    public ReviewPageView reviews(RestaurantInfo restaurant, @Argument int page, @Argument int size) {
        PageResponse<ReviewResponse> found = require(reviews, "отзывов")
                .getRestaurantReviews(restaurant.id(), new PageRequestParams(page, clamp(size)));

        return new ReviewPageView(found.content(), found.page(), found.size(), found.totalElements(), found.totalPages());
    }

    @SchemaMapping(typeName = "Restaurant")
    public List<RecommendedItem> popularDishes(RestaurantInfo restaurant, @Argument int limit) {
        return require(recommendations, "рекомендаций").popularInRestaurant(restaurant.id(), clamp(limit));
    }

    /**
     * Продажи — единственное закрытое поле ресторана. Проверка здесь, на поле, а не на входе:
     * тот же запрос с рейтингом и отзывами открыт всем, и закрывать его целиком из-за одного
     * поля было бы неверно.
     */
    @SchemaMapping(typeName = "Restaurant")
    public SalesReportView sales(RestaurantInfo restaurant, @Argument String from, @Argument String to) {
        if (currentUser.id().isEmpty()) {
            throw new NotAuthenticatedException("Продажи ресторана видит только вошедший управляющий");
        }

        if (!access.managesRestaurant(restaurant.id())) {
            throw new AccessDeniedForResourceException("Продажи видит только управляющий ресторана");
        }

        LocalDate fromDay = LocalDate.parse(from);
        LocalDate toDay = LocalDate.parse(to);

        return SalesReportView.of(fromDay, toDay, require(analytics, "аналитики").dailySales(restaurant.id(), fromDay, toDay));
    }

    @SchemaMapping(typeName = "Dish")
    public List<RecommendedItem> orderedTogether(DishRef dish, @Argument int limit) {
        return require(recommendations, "рекомендаций").orderedTogetherWith(dish.id(), clamp(limit));
    }

    private static <T> T require(ObjectProvider<T> provider, String module) {
        T service = provider.getIfAvailable();

        if (service == null) {
            throw new ModuleDisabledException("Модуль " + module + " выключен в этой конфигурации");
        }

        return service;
    }

    /** Предел на размер выдачи: аргумент приходит от клиента, и без него list(10000) — тоже запрос. */
    private static int clamp(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    /** Блюдо как точка входа: его поля разрешаются отдельно, по запросу. */
    public record DishRef(Long id) {
    }

    public record RatingView(long total, double average, List<Bucket> histogram, List<RatingSummary.TagCount> topTags) {

        static RatingView of(RatingSummary summary) {
            List<Bucket> buckets = summary.histogram().entrySet().stream()
                    .map(entry -> new Bucket(entry.getKey(), entry.getValue()))
                    .sorted((left, right) -> Integer.compare(right.stars(), left.stars()))
                    .toList();

            return new RatingView(summary.total(), summary.average(), buckets, summary.topTags());
        }

        public record Bucket(int stars, long count) {
        }
    }

    public record ReviewPageView(List<ReviewResponse> items, int page, int size, long totalElements, int totalPages) {
    }

    public record SalesReportView(String from, String to, long totalOrders, String totalRevenue, List<Day> days) {

        static SalesReportView of(LocalDate from, LocalDate to, List<DailySales> days) {
            long orders = days.stream().mapToLong(DailySales::orders).sum();
            BigDecimal revenue = days.stream().map(DailySales::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);

            return new SalesReportView(
                    from.toString(),
                    to.toString(),
                    orders,
                    revenue.toPlainString(),
                    days.stream()
                            .map(day -> new Day(day.day().toString(), day.orders(),
                                    day.revenue().toPlainString(), day.averageCheck().toPlainString()))
                            .toList()
            );
        }

        public record Day(String day, long orders, String revenue, String averageCheck) {
        }
    }
}
