package com.dima.fooddelivery.menu.service;


import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.menu.api.MenuCategoryResponse;
import com.dima.fooddelivery.menu.api.MenuItemResponse;
import com.dima.fooddelivery.menu.api.RestaurantMenuResponse;
import com.dima.fooddelivery.menu.persistence.MenuRow;
import com.dima.fooddelivery.menu.persistence.MenuRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final JdbcTemplate jdbcTemplate;
    private final MenuRowMapper menuRowMapper;

    @Transactional(readOnly = true)
    public RestaurantMenuResponse getRestaurantMenu(Long restaurantId) {
        log.info(
                "Начинаем загрузку меню ресторана: restaurantId={}",
                restaurantId
        );

        validateRestaurantExists(restaurantId);

        String sql = """
                SELECT 
                mc.id as category_id,
                mc.name as category_name,
                mc.sort_order as category_sort_order,
                
                mi.id as item_id,
                mi.name as item_name,
                mi.description as item_description,
                mi.price as item_price,
                mi.is_available as item_available,
                mi.sort_order as item_sort_order
                from food_app.menu_category mc
                left join food_app.menu_item mi
                on mi.category_id = mc.id
                where mc.restaurant_id = ?
                order by mc.sort_order, mc.id, mi.sort_order, mi.id
                """;

        List<MenuRow> rows = jdbcTemplate.query(sql, menuRowMapper, restaurantId);

        RestaurantMenuResponse response = buildMenuResponse(restaurantId, rows);

        log.info(
                "Меню ресторана загружено: restaurantId={}, categoriesCount={}",
                restaurantId,
                response.categories().size()
        );

        return response;
    }



    private void validateRestaurantExists(Long restaurantId) {
        String sql = """
                SELECT EXISTS (
                SELECT 1
                FROM food_app.restaurant r
                WHERE r.id = ?
                )
                """;

        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, restaurantId);

        if (!Boolean.TRUE.equals(exists)) {
            log.warn(
                    "Ресторан не найден при загрузке меню: restaurantId={}",
                    restaurantId
            );

            throw new ResourceNotFoundException(
                    "Ресторан с id=" + restaurantId + " не найден"
            );
        }
    }

    private RestaurantMenuResponse buildMenuResponse(Long restaurantId, List<MenuRow> rows) {
        Map<Long, MenuCategoryAccumulator> categoriesMap = new LinkedHashMap<>();

        for (MenuRow row : rows) {
            MenuCategoryAccumulator category = categoriesMap.computeIfAbsent(
                    row.categoryId(),
                    id -> new MenuCategoryAccumulator(row.categoryId(), row.categoryName())
            );

            if (row.itemId() != null) {
                category.items.add(
                        new MenuItemResponse(
                                row.itemId(),
                                row.itemName(),
                                row.itemDescription(),
                                row.itemPrice(),
                                Boolean.TRUE.equals(row.itemAvailable())
                        )
                );
            }
        }

        List<MenuCategoryResponse> categories = categoriesMap.values().stream()
                .map(category -> new MenuCategoryResponse(
                        category.id,
                        category.name,
                        category.items
                ))
                .toList();

        return new RestaurantMenuResponse(restaurantId,categories);
    }

    private static class MenuCategoryAccumulator {
        private final Long id;
        private final String name;
        private final List<MenuItemResponse> items =  new ArrayList<>();

        private MenuCategoryAccumulator(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
