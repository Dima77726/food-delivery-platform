package com.dima.fooddelivery.menu.domain;

import java.math.BigDecimal;

/**
 * Блюдо в меню.
 *
 * <p>Два разных признака «нет в продаже», и путать их нельзя:
 * <ul>
 *   <li>{@code available} — временно закончилось, вернётся завтра. Обратимо, управляется
 *       из зала одним переключателем;</li>
 *   <li>{@code archived} — убрано из меню навсегда. Строка остаётся в базе, потому что на неё
 *       ссылаются позиции старых заказов, но в меню больше не показывается.</li>
 * </ul>
 */
public record MenuItem(
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
