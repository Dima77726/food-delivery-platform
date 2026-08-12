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
            rs.getInt("category_sort_order"),
            rs.getBoolean("category_archived"),
            rs.getObject("item_id", Long.class),
            rs.getString("item_name"),
            rs.getString("item_description"),
            rs.getBigDecimal("item_price"),
            rs.getObject("item_sort_order", Integer.class),
            rs.getObject("item_available", Boolean.class),
            rs.getObject("item_archived", Boolean.class)
    );

    /**
     * Фильтр по архиву вынесен в параметр запроса, а не в конкатенацию SQL: витрине нужны
     * только живые позиции, владельцу — все, и это одна и та же выборка с одним флагом.
     */
    private static final String SELECT_MENU = """
            SELECT
                mc.id           AS category_id,
                mc.name         AS category_name,
                mc.sort_order   AS category_sort_order,
                mc.is_archived  AS category_archived,
                mi.id           AS item_id,
                mi.name         AS item_name,
                mi.description  AS item_description,
                mi.price        AS item_price,
                mi.sort_order   AS item_sort_order,
                mi.is_available AS item_available,
                mi.is_archived  AS item_archived
            FROM menu_category mc
            LEFT JOIN menu_item mi
                   ON mi.category_id = mc.id
                  AND (:includeArchived OR mi.is_archived = FALSE)
            WHERE mc.restaurant_id = :restaurantId
              AND (:includeArchived OR mc.is_archived = FALSE)
            ORDER BY mc.sort_order, mc.id, mi.sort_order, mi.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RestaurantMenu findMenu(Long restaurantId, boolean includeArchived) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("includeArchived", includeArchived);

        List<MenuRow> rows = jdbc.query(SELECT_MENU, params, MENU_ROW);

        return new RestaurantMenu(restaurantId, assembleCategories(rows));
    }

    /**
     * Блюдо в контексте конкретного ресторана.
     *
     * <p>JOIN с категорией нужен именно для проверки принадлежности: без него клиент мог бы
     * подложить {@code menuItemId} из чужого ресторана и получить в корзину блюдо, которого
     * в этом заведении нет. Архивные позиции сюда не попадают — их нельзя заказать.
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
                          AND mi.is_archived = FALSE
                          AND mc.is_archived = FALSE
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

    // --- Категории ----------------------------------------------------------------------------

    public Long insertCategory(Long restaurantId, String name, int sortOrder) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("name", name)
                .addValue("sortOrder", sortOrder);

        return jdbc.queryForObject(
                """
                        INSERT INTO menu_category (restaurant_id, name, sort_order)
                        VALUES (:restaurantId, :name, :sortOrder)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int updateCategory(Long categoryId, String name, Integer sortOrder) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("name", name)
                .addValue("sortOrder", sortOrder);

        return jdbc.update(
                """
                        UPDATE menu_category
                        SET name = COALESCE(:name, name),
                            sort_order = COALESCE(:sortOrder, sort_order),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :categoryId
                          AND is_archived = FALSE
                        """,
                params
        );
    }

    /**
     * Архивирует категорию вместе со всеми её позициями.
     *
     * <p>Одним запросом на каждую таблицу, а не выборкой позиций в Java: количество блюд
     * в категории заранее неизвестно, а результат обязан быть атомарным.
     */
    public int archiveCategory(Long categoryId) {
        SqlParameterSource params = new MapSqlParameterSource("categoryId", categoryId);

        jdbc.update(
                """
                        UPDATE menu_item
                        SET is_archived = TRUE,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE category_id = :categoryId
                          AND is_archived = FALSE
                        """,
                params
        );

        return jdbc.update(
                """
                        UPDATE menu_category
                        SET is_archived = TRUE,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :categoryId
                          AND is_archived = FALSE
                        """,
                params
        );
    }

    public boolean categoryBelongsToRestaurant(Long restaurantId, Long categoryId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("categoryId", categoryId);

        Boolean exists = jdbc.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM menu_category
                            WHERE id = :categoryId AND restaurant_id = :restaurantId
                        )
                        """,
                params,
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    // --- Позиции ------------------------------------------------------------------------------

    public Long insertItem(Long categoryId, String name, String description, BigDecimal price, int sortOrder) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("price", price)
                .addValue("sortOrder", sortOrder);

        return jdbc.queryForObject(
                """
                        INSERT INTO menu_item (category_id, name, description, price, sort_order, is_available)
                        VALUES (:categoryId, :name, :description, :price, :sortOrder, TRUE)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int updateItem(Long itemId, String name, String description, BigDecimal price, Integer sortOrder) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("itemId", itemId)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("price", price)
                .addValue("sortOrder", sortOrder);

        return jdbc.update(
                """
                        UPDATE menu_item
                        SET name = COALESCE(:name, name),
                            description = COALESCE(:description, description),
                            price = COALESCE(:price, price),
                            sort_order = COALESCE(:sortOrder, sort_order),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :itemId
                          AND is_archived = FALSE
                        """,
                params
        );
    }

    public int setItemAvailability(Long itemId, boolean available) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("itemId", itemId)
                .addValue("available", available);

        return jdbc.update(
                """
                        UPDATE menu_item
                        SET is_available = :available,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :itemId
                          AND is_archived = FALSE
                        """,
                params
        );
    }

    public int archiveItem(Long itemId) {
        return jdbc.update(
                """
                        UPDATE menu_item
                        SET is_archived = TRUE,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :itemId
                          AND is_archived = FALSE
                        """,
                new MapSqlParameterSource("itemId", itemId)
        );
    }

    public boolean itemBelongsToRestaurant(Long restaurantId, Long itemId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("itemId", itemId);

        Boolean exists = jdbc.queryForObject(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM menu_item mi
                            JOIN menu_category mc ON mc.id = mi.category_id
                            WHERE mi.id = :itemId AND mc.restaurant_id = :restaurantId
                        )
                        """,
                params,
                Boolean.class
        );

        return Boolean.TRUE.equals(exists);
    }

    public Optional<MenuItem> findItemById(Long itemId) {
        return jdbc.query(
                """
                        SELECT id, category_id, name, description, price,
                               sort_order, is_available, is_archived
                        FROM menu_item
                        WHERE id = :itemId
                        """,
                new MapSqlParameterSource("itemId", itemId),
                (rs, rowNum) -> new MenuItem(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBigDecimal("price"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("is_available"),
                        rs.getBoolean("is_archived")
                )
        ).stream().findFirst();
    }

    public Optional<MenuCategory> findCategoryById(Long categoryId) {
        return jdbc.query(
                """
                        SELECT id, name, sort_order, is_archived
                        FROM menu_category
                        WHERE id = :categoryId
                        """,
                new MapSqlParameterSource("categoryId", categoryId),
                (rs, rowNum) -> new MenuCategory(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("is_archived"),
                        List.of()
                )
        ).stream().findFirst();
    }

    private List<MenuCategory> assembleCategories(List<MenuRow> rows) {
        Map<Long, CategoryAccumulator> byCategory = new LinkedHashMap<>();

        for (MenuRow row : rows) {
            CategoryAccumulator category = byCategory.computeIfAbsent(
                    row.categoryId(),
                    id -> new CategoryAccumulator(row)
            );

            if (row.itemId() != null) {
                category.items.add(new MenuItem(
                        row.itemId(),
                        row.categoryId(),
                        row.itemName(),
                        row.itemDescription(),
                        row.itemPrice(),
                        row.itemSortOrder() == null ? 0 : row.itemSortOrder(),
                        Boolean.TRUE.equals(row.itemAvailable()),
                        Boolean.TRUE.equals(row.itemArchived())
                ));
            }
        }

        return byCategory.values().stream()
                .map(accumulator -> new MenuCategory(
                        accumulator.first.categoryId(),
                        accumulator.first.categoryName(),
                        accumulator.first.categorySortOrder(),
                        accumulator.first.categoryArchived(),
                        accumulator.items
                ))
                .toList();
    }

    private record MenuRow(
            Long categoryId,
            String categoryName,
            int categorySortOrder,
            boolean categoryArchived,
            Long itemId,
            String itemName,
            String itemDescription,
            BigDecimal itemPrice,
            Integer itemSortOrder,
            Boolean itemAvailable,
            Boolean itemArchived
    ) {
    }

    private static final class CategoryAccumulator {

        private final MenuRow first;
        private final List<MenuItem> items = new ArrayList<>();

        private CategoryAccumulator(MenuRow first) {
            this.first = first;
        }
    }
}
