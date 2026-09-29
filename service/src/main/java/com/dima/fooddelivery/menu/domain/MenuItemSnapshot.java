package com.dima.fooddelivery.menu.domain;

import java.math.BigDecimal;

/**
 * Блюдо в том виде, в каком его видит модуль Cart при добавлении в корзину:
 * идентификатор, цена на текущий момент, название и признак доступности.
 *
 * <p>Отдельный тип, а не {@link MenuItem}, потому что это межмодульный контракт. Модулю Cart
 * не нужны ни описание, ни порядок сортировки, и он не должен от них зависеть.
 */
public record MenuItemSnapshot(
        Long id,
        String name,
        BigDecimal price,
        boolean available
) {
}
