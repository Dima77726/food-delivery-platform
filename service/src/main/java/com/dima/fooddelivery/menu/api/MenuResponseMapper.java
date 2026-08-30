package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.generated.model.ManagedMenu;
import com.dima.fooddelivery.generated.model.ManagedMenuCategory;
import com.dima.fooddelivery.generated.model.ManagedMenuItem;
import com.dima.fooddelivery.generated.model.MenuCategory;
import com.dima.fooddelivery.generated.model.MenuItem;
import com.dima.fooddelivery.generated.model.RestaurantMenu;

/**
 * Два представления одного меню.
 *
 * <p>Витрине уходит {@link RestaurantMenu} без служебных полей: посетителю нечего делать
 * с порядком сортировки и признаком архива, потому что архивные позиции до него не доходят.
 * Владельцу уходит {@link ManagedMenu}, где видно всё, включая скрытое, — иначе он не смог бы
 * найти то, что сам архивировал.
 *
 * <p>Разделение закреплено в спецификации двумя наборами схем, а не одним с необязательными
 * полями. Схема с полями «иногда есть, иногда нет» вынуждает каждого потребителя гадать,
 * в каком он случае.
 */
public final class MenuResponseMapper {

    public static RestaurantMenu toResponse(com.dima.fooddelivery.menu.domain.RestaurantMenu menu) {
        RestaurantMenu response = new RestaurantMenu().restaurantId(menu.restaurantId());

        menu.categories().stream().map(MenuResponseMapper::toPublicResponse).forEach(response::addCategoriesItem);

        return response;
    }

    public static ManagedMenu toManagedResponse(com.dima.fooddelivery.menu.domain.RestaurantMenu menu) {
        ManagedMenu response = new ManagedMenu().restaurantId(menu.restaurantId());

        menu.categories().stream().map(MenuResponseMapper::toManagedResponse).forEach(response::addCategoriesItem);

        return response;
    }

    public static ManagedMenuItem toManagedResponse(com.dima.fooddelivery.menu.domain.MenuItemView item) {
        return new ManagedMenuItem()
                .id(item.id())
                .categoryId(item.categoryId())
                .name(item.name())
                .description(item.description())
                .price(item.price())
                .sortOrder(item.sortOrder())
                .available(item.available())
                .archived(item.archived());
    }

    public static ManagedMenuCategory toManagedResponse(com.dima.fooddelivery.menu.domain.MenuCategoryView category) {
        ManagedMenuCategory response = new ManagedMenuCategory()
                .id(category.id())
                .name(category.name())
                .sortOrder(category.sortOrder())
                .archived(category.archived());

        category.items().stream().map(MenuResponseMapper::toManagedResponse).forEach(response::addItemsItem);

        return response;
    }

    private static MenuCategory toPublicResponse(com.dima.fooddelivery.menu.domain.MenuCategoryView category) {
        MenuCategory response = new MenuCategory()
                .id(category.id())
                .name(category.name());

        category.items().stream().map(MenuResponseMapper::toPublicResponse).forEach(response::addItemsItem);

        return response;
    }

    private static MenuItem toPublicResponse(com.dima.fooddelivery.menu.domain.MenuItemView item) {
        return new MenuItem()
                .id(item.id())
                .name(item.name())
                .description(item.description())
                .price(item.price())
                .available(item.available());
    }

    private MenuResponseMapper() {
    }
}
