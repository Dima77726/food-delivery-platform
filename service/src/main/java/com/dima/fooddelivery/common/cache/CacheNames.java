package com.dima.fooddelivery.common.cache;

/**
 * Имена кэшей.
 *
 * <p>Константы, а не строковые литералы в аннотациях. Опечатка в {@code @CacheEvict}
 * не вызовет ни ошибки компиляции, ни исключения в рантайме: Spring просто создаст ещё один
 * пустой кэш и будет исправно чистить его вместо нужного. Данные при этом останутся
 * протухшими, и найти такое можно только по жалобе пользователя.
 */
public final class CacheNames {

    /** Список ресторанов для витрины. */
    public static final String RESTAURANTS = "restaurants";

    /** Меню ресторана в публичном виде, без архивных позиций. Ключ — идентификатор ресторана. */
    public static final String RESTAURANT_MENU = "restaurant-menu";

    private CacheNames() {
    }
}
