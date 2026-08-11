package com.dima.fooddelivery.user.domain;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Пользователь платформы.
 *
 * <p>{@code passwordHash} лежит в доменном типе, но наружу через API не выходит: за этим следит
 * маппер в пакете {@code api}, который просто не переносит это поле в DTO. Отдельного типа
 * «пользователь без пароля» здесь нет намеренно — лишняя сущность ради одного поля.
 */
public record AppUser(
        Long id,
        String email,
        String passwordHash,
        String fullName,
        String phone,
        boolean enabled,
        Set<UserRole> roles,
        OffsetDateTime createdAt
) {

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }
}
