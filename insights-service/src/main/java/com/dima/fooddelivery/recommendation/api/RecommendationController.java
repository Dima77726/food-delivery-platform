package com.dima.fooddelivery.recommendation.api;

import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Рекомендации по графу заказов.
 *
 * <p>Два запроса для пользователя и два служебных. Служебные лежат под {@code /api/v1/admin}
 * и нужны затем, что проекция графа идёт по расписанию: без ручного запуска демонстрация
 * и тест зависели бы от того, успел ли сработать таймер.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.NEO4J, havingValue = "true")
@Tag(name = "Recommendation", description = "Рекомендации по графу заказов (Neo4j)")
@SecurityRequirement(name = "bearer-jwt")
public class RecommendationController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final RecommendationService recommendationService;

    @GetMapping("/api/v1/customers/{customerId}/recommendations")
    @PreAuthorize("@access.isSelf(#customerId)")
    @Operation(summary = "Что заказывают те, кто брал то же самое")
    public List<RecommendedItemResponse> getRecommendations(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Min(value = 1, message = "limit не меньше 1")
            @Max(value = MAX_LIMIT, message = "limit не больше " + MAX_LIMIT)
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        return RecommendedItemResponse.of(recommendationService.recommendForCustomer(customerId, limit));
    }

    @GetMapping("/api/v1/menu-items/{menuItemId}/ordered-together")
    @Operation(summary = "Что чаще всего заказывают вместе с этим блюдом")
    public List<RecommendedItemResponse> getOrderedTogether(
            @Positive(message = "menuItemId должен быть положительным числом")
            @PathVariable Long menuItemId,

            @Min(value = 1, message = "limit не меньше 1")
            @Max(value = MAX_LIMIT, message = "limit не больше " + MAX_LIMIT)
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        return RecommendedItemResponse.of(recommendationService.orderedTogetherWith(menuItemId, limit));
    }

    @PostMapping("/api/v1/admin/recommendations/project")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Догнать граф на одну пачку заказов, не дожидаясь таймера")
    public ProjectionResultResponse projectNow() {
        return new ProjectionResultResponse(recommendationService.projectNextBatch());
    }

    @PostMapping("/api/v1/admin/recommendations/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Стереть граф и построить его заново по всем заказам")
    public ProjectionResultResponse rebuild() {
        log.warn("Запрошено полное перестроение графа рекомендаций");

        return new ProjectionResultResponse(recommendationService.rebuild());
    }
}
