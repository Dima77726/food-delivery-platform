package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;

/**
 * Два представления одного меню.
 *
 * <p>Витрине уходит {@link RestaurantMenuResponse} без служебных полей: клиенту нечего делать
 * с порядком сортировки и признаком архива, потому что архивные позиции до него не доходят.
 * Владельцу уходит {@link ManagedMenuResponse}, где видно всё, включая скрытое, — иначе он
 * не смог бы найти то, что сам архивировал.
 */
public final class MenuResponseMapper {

    public static RestaurantMenuResponse toResponse(RestaurantMenu menu) {
        return new RestaurantMenuResponse(
                menu.restaurantId(),
                menu.categories().stream().map(MenuResponseMapper::toPublicResponse).toList()
        );
    }

    public static ManagedMenuResponse toManagedResponse(RestaurantMenu menu) {
        return new ManagedMenuResponse(
                menu.restaurantId(),
                menu.categories().stream().map(MenuResponseMapper::toManagedResponse).toList()
        );
    }

    public static ManagedMenuItemResponse toManagedResponse(MenuItem item) {
        return new ManagedMenuItemResponse(
                item.id(),
                item.categoryId(),
                item.name(),
                item.description(),
                item.price(),
                item.sortOrder(),
                item.available(),
                item.archived()
        );
    }

    public static ManagedMenuCategoryResponse toManagedResponse(MenuCategory category) {
        return new ManagedMenuCategoryResponse(
                category.id(),
                category.name(),
                category.sortOrder(),
                category.archived(),
                category.items().stream().map(MenuResponseMapper::toManagedResponse).toList()
        );
    }

    private static MenuCategoryResponse toPublicResponse(MenuCategory category) {
        return new MenuCategoryResponse(
                category.id(),
                category.name(),
                category.items().stream().map(MenuResponseMapper::toPublicResponse).toList()
        );
    }

    private static MenuItemResponse toPublicResponse(MenuItem item) {
        return new MenuItemResponse(
                item.id(),
                item.name(),
                item.description(),
                item.price(),
                item.available()
        );
    }

    private MenuResponseMapper() {
    }
}
