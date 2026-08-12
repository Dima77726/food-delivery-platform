package com.dima.fooddelivery.menu.api;

import java.math.BigDecimal;

/**
 * Блюдо в глазах владельца ресторана: с порядком сортировки и обоими признаками скрытия.
 * Клиенту эти поля не нужны — он архивных позиций вообще не видит.
 */
public record ManagedMenuItemResponse(
        Long id,
        Long categoryId,
        String name,
        String description,
        BigDecimal price,
        int sortOrder,
        boolean available,
        boolean archived
) {
}
