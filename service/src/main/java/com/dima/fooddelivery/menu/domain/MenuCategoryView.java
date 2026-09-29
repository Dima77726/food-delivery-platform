package com.dima.fooddelivery.menu.domain;

import java.util.List;

/**
 * Категория меню в том виде, в каком её отдаёт сервис. Проекция сущности
 * {@link MenuCategory} — о причинах см. {@link MenuItemView}.
 */
public record MenuCategoryView(
        Long id,
        String name,
        int sortOrder,
        boolean archived,
        List<MenuItemView> items
) {

    /**
     * Собирает проекцию категории.
     *
     * @param includeArchived показывать ли архивные блюда: {@code true} для меню владельца,
     *                        {@code false} для витрины
     */
    public static MenuCategoryView of(MenuCategory category, boolean includeArchived) {
        List<MenuItemView> items = category.getItems().stream()
                .filter(item -> includeArchived || !item.isArchived())
                .map(MenuItemView::of)
                .toList();

        return new MenuCategoryView(
                category.getId(),
                category.getName(),
                category.getSortOrder(),
                category.isArchived(),
                items
        );
    }

    /**
     * Категория без блюд — для ответов на создание и изменение самой категории.
     *
     * <p>Отдельный метод нужен, чтобы не трогать коллекцию {@link MenuCategory#getItems()}:
     * обращение к ней инициализировало бы ленивую связь лишним SELECT'ом ради данных,
     * которых в этом ответе всё равно нет.
     */
    public static MenuCategoryView withoutItems(MenuCategory category) {
        return new MenuCategoryView(
                category.getId(),
                category.getName(),
                category.getSortOrder(),
                category.isArchived(),
                List.of()
        );
    }
}
