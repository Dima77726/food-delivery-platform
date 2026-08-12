package com.dima.fooddelivery.menu.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Изменение цены здесь не затрагивает существующие корзины и заказы: и cart_item,
 * и customer_order_item хранят собственную копию цены на момент добавления.
 */
public record UpdateMenuItemRequest(
        @Size(max = 255, message = "название не длиннее 255 символов")
        String name,

        @Size(max = 100, message = "описание не длиннее 100 символов")
        String description,

        @PositiveOrZero(message = "цена не может быть отрицательной")
        @Digits(integer = 8, fraction = 2, message = "цена: не больше 8 целых и 2 дробных знаков")
        BigDecimal price,

        @PositiveOrZero(message = "порядок сортировки не может быть отрицательным")
        Integer sortOrder
) {
}
