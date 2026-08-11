package com.dima.fooddelivery.admin.api;

import com.dima.fooddelivery.audit.api.AuditEntryResponse;
import com.dima.fooddelivery.audit.api.AuditResponseMapper;
import com.dima.fooddelivery.audit.service.AuditService;
import com.dima.fooddelivery.user.api.UserResponse;
import com.dima.fooddelivery.user.api.UserResponseMapper;
import com.dima.fooddelivery.user.domain.UserRole;
import com.dima.fooddelivery.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Служебные операции.
 *
 * <p>Путь {@code /api/v1/admin/**} закрыт ролью ADMIN ещё в {@code SecurityConfig} — на уровне
 * фильтра, до того как запрос дойдёт до контроллера. Дублирующий {@code @PreAuthorize} на классе
 * стоит намеренно: если кто-то однажды перепишет цепочку фильтров и забудет про этот префикс,
 * вторая проверка не даст админке открыться всем подряд.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Служебные операции: пользователи, роли, аудит")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final int MAX_AUDIT_PAGE_SIZE = 500;

    private final UserService userService;
    private final AuditService auditService;

    @GetMapping("/users")
    @Operation(summary = "Все пользователи платформы")
    public List<UserResponse> getUsers() {
        return UserResponseMapper.toResponses(userService.getAllUsers());
    }

    @PostMapping("/users/{userId}/roles/{role}")
    @Operation(summary = "Выдать пользователю роль")
    public UserResponse grantRole(
            @Positive @PathVariable Long userId,
            @PathVariable UserRole role
    ) {
        log.info("Выдача роли: userId={}, role={}", userId, role);

        UserResponse response = UserResponseMapper.toResponse(userService.grantRole(userId, role));

        auditService.recordSuccess("GRANT_ROLE", "USER", String.valueOf(userId), "Роль: " + role);

        return response;
    }

    @PostMapping("/users/{userId}/enable")
    @Operation(summary = "Разблокировать пользователя")
    public UserResponse enableUser(@Positive @PathVariable Long userId) {
        UserResponse response = UserResponseMapper.toResponse(userService.setEnabled(userId, true));

        auditService.recordSuccess("ENABLE_USER", "USER", String.valueOf(userId), null);

        return response;
    }

    @PostMapping("/users/{userId}/disable")
    @Operation(summary = "Заблокировать пользователя")
    public UserResponse disableUser(@Positive @PathVariable Long userId) {
        UserResponse response = UserResponseMapper.toResponse(userService.setEnabled(userId, false));

        auditService.recordSuccess("DISABLE_USER", "USER", String.valueOf(userId), null);

        return response;
    }

    @PostMapping("/users/{userId}/restaurants/{restaurantId}")
    @Operation(summary = "Назначить пользователя управляющим рестораном")
    public UserResponse assignRestaurantManager(
            @Positive @PathVariable Long userId,
            @Positive @PathVariable Long restaurantId
    ) {
        userService.assignRestaurantManager(userId, restaurantId);

        auditService.recordSuccess(
                "ASSIGN_RESTAURANT_MANAGER",
                "RESTAURANT",
                String.valueOf(restaurantId),
                "Управляющий: " + userId
        );

        return UserResponseMapper.toResponse(userService.requireUser(userId));
    }

    @GetMapping("/audit")
    @Operation(summary = "Последние записи журнала аудита")
    public List<AuditEntryResponse> getAuditLog(
            @RequestParam(defaultValue = "100")
            @Positive
            @Max(value = MAX_AUDIT_PAGE_SIZE, message = "limit не больше " + MAX_AUDIT_PAGE_SIZE)
            int limit
    ) {
        return AuditResponseMapper.toResponses(auditService.getRecent(limit));
    }

    @GetMapping("/audit/resources/{resourceType}/{resourceId}")
    @Operation(summary = "История действий над конкретным ресурсом")
    public List<AuditEntryResponse> getAuditForResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId,
            @RequestParam(defaultValue = "100")
            @Positive
            @Max(value = MAX_AUDIT_PAGE_SIZE, message = "limit не больше " + MAX_AUDIT_PAGE_SIZE)
            int limit
    ) {
        return AuditResponseMapper.toResponses(
                auditService.getByResource(resourceType, resourceId, limit)
        );
    }
}
