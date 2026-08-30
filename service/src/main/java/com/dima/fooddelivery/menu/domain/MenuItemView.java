package com.dima.fooddelivery.menu.domain;

import java.math.BigDecimal;

/**
 * Блюдо в том виде, в каком его отдаёт сервис.
 *
 * <p>Отдельный тип от сущности {@link MenuItem}, и на то две причины, обе практические.
 *
 * <p>Первая — {@code open-in-view: false}. Сущность живёт ровно столько, сколько длится
 * транзакция; контроллер получает объект уже после её закрытия, и обращение к ленивой связи
 * дало бы LazyInitializationException. Record отсоединять не от чего.
 *
 * <p>Вторая — кэш. Публичное меню уезжает в Redis как JSON, а сериализовать сущность нельзя:
 * двусторонняя связь категория ⇄ блюдо образует цикл, а коллекцию Hibernate подменяет своей
 * реализацией {@code PersistentBag}, имя которой не пройдёт проверку типов при чтении обратно.
 *
 * <p>Общее правило, которое из этого следует: сущность — не DTO. Наружу едет проекция.
 */
public record MenuItemView(
        Long id,
        Long categoryId,
        String name,
        String description,
        BigDecimal price,
        int sortOrder,
        boolean available,
        boolean archived
) {

    public static MenuItemView of(MenuItem item) {
        return new MenuItemView(
                item.getId(),
                item.getCategoryId(),
                item.getName(),
                item.getDescription(),
                item.getPrice(),
                item.getSortOrder(),
                item.isAvailable(),
                item.isArchived()
        );
    }
}
