package com.dima.fooddelivery.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Идентификатор текущего пользователя из токена.
 *
 * <p>Контракт токена общий для всех сервисов: числовой идентификатор лежит в {@code sub},
 * роли — в claim {@code roles}. Выпускает токены монолит.
 */
@Component
public class CurrentUser {

    public Optional<Long> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        return Optional.of(Long.valueOf(jwt.getSubject()));
    }

    public Long requireId() {
        return id().orElseThrow(() -> new IllegalStateException(
                "Запрос дошёл до кода, требующего аутентификации, без пользователя в контексте"
        ));
    }
}
