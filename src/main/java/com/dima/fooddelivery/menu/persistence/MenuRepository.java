package com.dima.fooddelivery.menu.persistence;

import com.dima.fooddelivery.menu.domain.MenuCategory;
import com.dima.fooddelivery.menu.domain.MenuItem;
import com.dima.fooddelivery.menu.domain.MenuItemSnapshot;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MenuRepository {

    private static final RowMapper<MenuRow> MENU_ROW = (rs, rowNum) -> new MenuRow(
            rs.getLong("category_id"),
            rs.getString("category_name"),
            rs.getObject("item_id", Long.class),
            rs.getString("item_name"),
            rs.getString("item_description"),
            rs.getBigDecimal("item_price"),
            rs.getObject("item_available", Boolean.class)
    );

    private final NamedParameterJdbcTemplate jdbc;

    public RestaurantMenu findMenuByRestaurantId(Long restaurantId) {
        List<MenuRow> rows = jdbc.query(
                """
                        SELECT
                            mc.id           AS category_id,
                            mc.name         AS category_name,
                            mi.id           AS item_id,
                            mi.name         AS item_name,
                            mi.description  AS item_description,
                            mi.price        AS item_price,
                            mi.is_available AS item_available
                        FROM menu_category mc
                        LEFT JOIN menu_item mi ON mi.category_id = mc.id
                        WHERE mc.restaurant_id = :restaurantId
                        ORDER BY mc.sort_order, mc.id, mi.sort_order, mi.id
                        """,
                new MapSqlParameterSource("restaurantId", restaurantId),
                MENU_ROW
        );

        return new RestaurantMenu(restaurantId, assembleCategories(rows));
    }

    /**
     * Блюдо в контексте конкретного ресторана.
     *
     * <p>JOIN с категорией нужен именно для проверки принадлежности: без него клиент мог бы
     * подложить {@code menuItemId} из чужого ресторана и получить в корзину блюдо, которого
     * в этом заведении нет.
     */
    public Optional<MenuItemSnapshot> findItemInRestaurant(Long restaurantId, Long menuItemId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("menuItemId", menuItemId);

        return jdbc.query(
                """
                        SELECT mi.id, mi.name, mi.price, mi.is_available
                        FROM menu_item mi
                        JOIN menu_category mc ON mc.id = mi.category_id
                        WHERE mc.restaurant_id = :restaurantId
                          AND mi.id = :menuItemId
                        """,
                params,
                (rs, rowNum) -> new MenuItemSnapshot(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBigDecimal("price"),
                        rs.getBoolean("is_available")
                )
        ).stream().findFirst();
    }

    private List<MenuCategory> assembleCategories(List<MenuRow> rows) {
        Map<Long, CategoryAccumulator> byCategory = new LinkedHashMap<>();

        for (MenuRow row : rows) {
            CategoryAccumulator category = byCategory.computeIfAbsent(
                    row.categoryId(),
                    id -> new CategoryAccumulator(row.categoryId(), row.categoryName())
            );

            if (row.itemId() != null) {
                category.items.add(new MenuItem(
                        row.itemId(),
                        row.itemName(),
                        row.itemDescription(),
                        row.itemPrice(),
                        Boolean.TRUE.equals(row.itemAvailable())
                ));
            }
        }

        return byCategory.values().stream()
                .map(accumulator -> new MenuCategory(accumulator.id, accumulator.name, accumulator.items))
                .toList();
    }

    private record MenuRow(
            Long categoryId,
            String categoryName,
            Long itemId,
            String itemName,
            String itemDescription,
            BigDecimal itemPrice,
            Boolean itemAvailable
    ) {
    }

    private static final class CategoryAccumulator {
        private final Long id;
        private final String name;
        private final List<MenuItem> items = new ArrayList<>();

        private CategoryAccumulator(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
