package com.dima.fooddelivery.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Проверки владения рестораном для {@code @PreAuthorize}: {@code @access.managesRestaurant(#id)}.
 *
 * <p>Имя бина и сигнатура — те же, что в монолите, поэтому аннотации на контроллерах
 * переехали без единой правки. Поменялось только то, откуда берётся ответ: раньше —
 * запрос к своей таблице, теперь — gRPC-вызов к владельцу пользователей.
 *
 * <p>Администратор проверяется по токену, без сетевого вызова: роль подписана в нём,
 * и спрашивать о ней кого-то ещё незачем.
 */
@Slf4j
@Component("access")
@RequiredArgsConstructor
public class ResourceAccessGuard {

    private static final String ADMIN_AUTHORITY = SecurityConfig.ROLE_PREFIX + "ADMIN";

    private final CurrentUser currentUser;
    private final RestaurantAccessClient restaurantAccessClient;

    public boolean managesRestaurant(Long restaurantId) {
        if (isAdmin()) {
            return true;
        }

        boolean manages = currentUser.id()
                .map(userId -> restaurantAccessClient.managesRestaurant(userId, restaurantId))
                .orElse(false);

        if (!manages) {
            log.warn(
                    "Попытка управлять чужим рестораном: restaurantId={}, currentUserId={}",
                    restaurantId,
                    currentUser.id().orElse(null)
            );
        }

        return manages;
    }

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
