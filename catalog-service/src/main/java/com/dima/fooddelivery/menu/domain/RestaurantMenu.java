package com.dima.fooddelivery.menu.domain;

import java.util.List;

/**
 * Меню ресторана целиком.
 *
 * <p>Таблицы за этим типом нет и сущностью он не является: это read-модель, собранная
 * из категорий и блюд. В Redis уезжает именно она.
 */
public record RestaurantMenu(
        Long restaurantId,
        List<MenuCategoryView> categories
) {

    /**
     * @param includeArchived {@code true} — меню владельца, видно всё, включая убранное
     *                        им самим; {@code false} — витрина для посетителя
     */
    public static RestaurantMenu of(Long restaurantId, List<MenuCategory> categories, boolean includeArchived) {
        List<MenuCategoryView> views = categories.stream()
                .filter(category -> includeArchived || !category.isArchived())
                .map(category -> MenuCategoryView.of(category, includeArchived))
                .toList();

        return new RestaurantMenu(restaurantId, views);
    }
}
