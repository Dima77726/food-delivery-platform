package com.dima.fooddelivery.support;

import com.dima.fooddelivery.catalog.CatalogClient;
import com.dima.fooddelivery.catalog.CatalogItem;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Каталог для интеграционных тестов монолита — без каталога.
 *
 * <p>Тестам корзины, заказа и оплаты нужен ответ на два вопроса — открыт ли ресторан и сколько
 * стоит блюдо, — но не нужен живой catalog-service: поднимать второй сервис ради каждого теста
 * SQL монолита значило бы проверять сразу всё и медленно. Поэтому каталог здесь заменён.
 *
 * <p>Ответы берутся из старых таблиц {@code restaurant} и {@code menu_item} в базе монолита —
 * тех самых, которые монолит после переезда каталога больше не читает. Для тестов это удобно:
 * {@link TestDataFactory} продолжает заводить рестораны и блюда одним INSERT, и ни один
 * существующий тест не пришлось переписывать.
 *
 * <p>Правила те же, что у каталога, вплоть до исключений: ресторан проверяется на активность,
 * блюдо ищется в пределах ресторана, архивное — «нет такого», снятое с продажи — «нельзя».
 * Совпадение с настоящим каталогом проверяют тесты самого каталога и тест gRPC-клиента.
 */
public class LegacyTablesCatalogClient implements CatalogClient {

    private final JdbcTemplate jdbcTemplate;

    public LegacyTablesCatalogClient(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void requireActiveRestaurant(Long restaurantId) {
        List<Boolean> active = jdbcTemplate.queryForList(
                "SELECT is_active FROM restaurant WHERE id = ?",
                Boolean.class,
                restaurantId
        );

        if (active.isEmpty()) {
            throw new ResourceNotFoundException("Ресторан с id=" + restaurantId + " не найден");
        }

        if (!active.get(0)) {
            throw new BusinessRuleViolationException("Ресторан с id=" + restaurantId + " сейчас недоступен для заказов");
        }
    }

    @Override
    public CatalogItem requireAvailableItem(Long restaurantId, Long menuItemId) {
        List<Row> rows = jdbcTemplate.query(
                """
                        SELECT mi.id, mi.name, mi.price, mi.is_available
                        FROM menu_item mi
                        JOIN menu_category mc ON mc.id = mi.category_id
                        WHERE mi.id = ?
                          AND mc.restaurant_id = ?
                          AND mi.is_archived = FALSE
                          AND mc.is_archived = FALSE
                        """,
                (rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBigDecimal("price"),
                        rs.getBoolean("is_available")
                ),
                menuItemId,
                restaurantId
        );

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Позиция меню с id=" + menuItemId + " не найдена в ресторане с id=" + restaurantId
            );
        }

        Row row = rows.get(0);

        if (!row.available()) {
            throw new BusinessRuleViolationException("Позиция меню с id=" + menuItemId + " сейчас недоступна");
        }

        return new CatalogItem(row.id(), row.name(), row.price());
    }

    private record Row(Long id, String name, BigDecimal price, boolean available) {
    }
}
