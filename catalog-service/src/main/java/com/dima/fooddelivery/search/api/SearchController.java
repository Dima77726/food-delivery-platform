package com.dima.fooddelivery.search.api;

import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.search.domain.ReindexResult;
import com.dima.fooddelivery.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Поиск по витрине.
 *
 * <p>Поиск открыт без токена — как и сама витрина: список ресторанов и меню видно без входа,
 * и поиск по ним обязан быть доступен на тех же условиях. Разрешение выдано в
 * {@code SecurityConfig}.
 *
 * <p>Ручная переиндексация лежит под {@code /api/v1/admin}: она читает всю витрину целиком
 * и запускать её кому попало незачем.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.ELASTICSEARCH, havingValue = "true")
@Tag(name = "Search", description = "Поиск по ресторанам и блюдам (Elasticsearch)")
public class SearchController {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    private final SearchService searchService;

    @GetMapping("/api/v1/search")
    @Operation(summary = "Поиск по названиям и описаниям ресторанов и блюд")
    public SearchResponse search(
            @Size(max = 200, message = "запрос не длиннее 200 символов")
            @RequestParam(name = "q", required = false) String query,

            @Size(max = 100, message = "название города не длиннее 100 символов")
            @RequestParam(required = false) String city,

            @Min(value = 1, message = "size не меньше 1")
            @Max(value = MAX_SIZE, message = "size не больше " + MAX_SIZE)
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        return SearchResponse.of(searchService.search(query, city, size));
    }

    @PostMapping("/api/v1/admin/search/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Перестроить поисковый индекс, не дожидаясь расписания")
    public ReindexResult reindex() {
        log.info("Запрошена ручная переиндексация витрины");

        return searchService.reindexAll();
    }
}
