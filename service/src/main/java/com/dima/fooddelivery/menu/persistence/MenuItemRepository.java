package com.dima.fooddelivery.menu.persistence;

import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.MenuItemSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /**
     * Блюдо в контексте конкретного ресторана, сразу в виде межмодульного контракта.
     *
     * <p>Условие по ресторану идёт через связь — {@code i.category.restaurantId}. Hibernate
     * сам превращает такой путь в JOIN с таблицей категорий, и писать его руками не нужно.
     * Проверка принадлежности здесь не формальность: без неё клиент подставил бы
     * {@code menuItemId} из чужого ресторана и получил в корзину блюдо, которого
     * в этом заведении нет.
     *
     * <p>{@code SELECT new ...} — конструкторная проекция: запрос возвращает готовый record,
     * а не сущность. Так модуль Cart получает ровно четыре нужных ему поля, в персистентный
     * контекст ничего не попадает, и случайно изменить цену блюда через этот путь невозможно.
     */
    @Query("""
            SELECT new com.dima.fooddelivery.menu.domain.MenuItemSnapshot(i.id, i.name, i.price, i.available)
            FROM MenuItem i
            WHERE i.id = :menuItemId
              AND i.category.restaurantId = :restaurantId
              AND i.archived = false
              AND i.category.archived = false
            """)
    Optional<MenuItemSnapshot> findOrderableInRestaurant(
            @Param("restaurantId") Long restaurantId,
            @Param("menuItemId") Long menuItemId
    );

    /**
     * Блюдо вместе с проверкой принадлежности ресторану — одним запросом с JOIN.
     *
     * <p>Подчёркивание в {@code Category_RestaurantId} — явно проставленная граница между
     * именем связи и именем поля внутри неё. Без него Spring Data сначала искал бы у блюда
     * собственное поле {@code categoryRestaurantId} и только потом догадывался бы
     * о проходе по связи.
     *
     * <p>Загружается именно сущность, а не проекция: вызывающему нужно её менять, а значит
     * она обязана попасть в персистентный контекст под грязную проверку.
     */
    Optional<MenuItem> findByIdAndCategory_RestaurantId(Long id, Long restaurantId);
}
