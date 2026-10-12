package com.dima.fooddelivery.search.api;

import com.dima.fooddelivery.search.domain.MenuItemDocument;
import com.dima.fooddelivery.search.domain.RestaurantDocument;
import com.dima.fooddelivery.search.domain.SearchResults;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ответ поиска.
 *
 * <p>Документ индекса наружу не отдаётся: в нём есть служебное поле {@code indexedAt},
 * которое клиенту не нужно и о котором ему знать незачем — это деталь того, как устроена
 * уборка индекса.
 */
public record SearchResponse(
        String query,
        List<FoundRestaurant> restaurants,
        List<FoundMenuItem> items
) {

    public record FoundRestaurant(
            Long restaurantId,
            String name,
            String description,
            String city,
            boolean active
    ) {
    }

    public record FoundMenuItem(
            Long menuItemId,
            Long restaurantId,
            String restaurantName,
            String city,
            String name,
            String description,
            BigDecimal price,
            boolean available
    ) {
    }

    public static SearchResponse of(SearchResults results) {
        return new SearchResponse(
                results.query(),
                results.restaurants().stream().map(SearchResponse::toResponse).toList(),
                results.items().stream().map(SearchResponse::toResponse).toList()
        );
    }

    private static FoundRestaurant toResponse(RestaurantDocument document) {
        return new FoundRestaurant(
                document.restaurantId(),
                document.name(),
                document.description(),
                document.city(),
                document.active()
        );
    }

    private static FoundMenuItem toResponse(MenuItemDocument document) {
        return new FoundMenuItem(
                document.menuItemId(),
                document.restaurantId(),
                document.restaurantName(),
                document.city(),
                document.name(),
                document.description(),
                // В индексе цена лежит числом с плавающей точкой, наружу отдаётся тем же
                // типом, что и везде в API. valueOf, а не new BigDecimal(double): второй
                // способ переносит в результат весь двоичный хвост вроде 349.00000000000006.
                BigDecimal.valueOf(document.price()),
                document.available()
        );
    }
}
