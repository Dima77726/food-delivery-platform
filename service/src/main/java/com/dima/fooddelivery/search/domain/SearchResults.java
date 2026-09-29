package com.dima.fooddelivery.search.domain;

import java.util.List;

/**
 * Результат поиска: рестораны и блюда отдельными списками.
 *
 * <p>Единый список был бы удобнее для реализации и хуже для пользователя: ресторан и блюдо —
 * разные сущности с разными карточками и разными действиями. Смешивать их в одну выдачу
 * означало бы заставить клиента разбирать тип каждого элемента и всё равно рисовать
 * два раздела.
 */
public record SearchResults(
        String query,
        List<RestaurantDocument> restaurants,
        List<MenuItemDocument> items
) {

    public static SearchResults empty(String query) {
        return new SearchResults(query, List.of(), List.of());
    }
}
