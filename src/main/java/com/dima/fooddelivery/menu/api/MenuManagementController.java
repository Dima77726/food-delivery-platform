package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Управление меню. Отдельный контроллер от {@link MenuController} не ради красоты:
 * тот отдаёт витрину анонимному посетителю, этот целиком закрыт проверкой управляющего.
 * Смешав их, легко однажды повесить публичный метод под общий {@code @PreAuthorize}
 * или, что хуже, наоборот.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/{restaurantId}/menu")
@Tag(name = "Menu management", description = "Управление меню владельцем ресторана")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("@access.managesRestaurant(#restaurantId)")
public class MenuManagementController {

    private final MenuService menuService;

    @GetMapping("/manage")
    @Operation(summary = "Меню целиком, включая скрытые и архивные позиции")
    public ManagedMenuResponse getManagedMenu(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        return menuService.getManagedMenu(restaurantId);
    }

    // --- Категории ----------------------------------------------------------------------------

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать категорию")
    public ManagedMenuCategoryResponse createCategory(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Valid @RequestBody CreateMenuCategoryRequest request
    ) {
        log.info("Создание категории меню: restaurantId={}, name={}", restaurantId, request.name());

        return menuService.createCategory(restaurantId, request);
    }

    @PatchMapping("/categories/{categoryId}")
    @Operation(summary = "Переименовать категорию или изменить её порядок")
    public ManagedMenuCategoryResponse updateCategory(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "categoryId должен быть положительным числом")
            @PathVariable Long categoryId,

            @Valid @RequestBody UpdateMenuCategoryRequest request
    ) {
        return menuService.updateCategory(restaurantId, categoryId, request);
    }

    /**
     * DELETE по смыслу, архивирование по факту: строки остаются в базе, потому что на них
     * ссылаются позиции прошлых заказов. Для клиента API это неотличимо от удаления.
     */
    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Убрать категорию из меню вместе с её блюдами")
    public void archiveCategory(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "categoryId должен быть положительным числом")
            @PathVariable Long categoryId
    ) {
        log.info("Архивирование категории: restaurantId={}, categoryId={}", restaurantId, categoryId);

        menuService.archiveCategory(restaurantId, categoryId);
    }

    // --- Позиции ------------------------------------------------------------------------------

    @PostMapping("/categories/{categoryId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить блюдо в категорию")
    public ManagedMenuItemResponse createItem(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "categoryId должен быть положительным числом")
            @PathVariable Long categoryId,

            @Valid @RequestBody CreateMenuItemRequest request
    ) {
        log.info("Создание блюда: restaurantId={}, categoryId={}, name={}",
                restaurantId, categoryId, request.name());

        return menuService.createItem(restaurantId, categoryId, request);
    }

    @PatchMapping("/items/{itemId}")
    @Operation(summary = "Изменить блюдо; на существующие корзины и заказы это не влияет")
    public ManagedMenuItemResponse updateItem(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "itemId должен быть положительным числом")
            @PathVariable Long itemId,

            @Valid @RequestBody UpdateMenuItemRequest request
    ) {
        return menuService.updateItem(restaurantId, itemId, request);
    }

    @PostMapping("/items/{itemId}/in-stock")
    @Operation(summary = "Вернуть блюдо в продажу")
    public ManagedMenuItemResponse markInStock(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "itemId должен быть положительным числом")
            @PathVariable Long itemId
    ) {
        return menuService.setItemAvailability(restaurantId, itemId, true);
    }

    @PostMapping("/items/{itemId}/out-of-stock")
    @Operation(summary = "Временно скрыть блюдо: закончилось")
    public ManagedMenuItemResponse markOutOfStock(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "itemId должен быть положительным числом")
            @PathVariable Long itemId
    ) {
        return menuService.setItemAvailability(restaurantId, itemId, false);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Убрать блюдо из меню навсегда")
    public void archiveItem(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "itemId должен быть положительным числом")
            @PathVariable Long itemId
    ) {
        log.info("Архивирование блюда: restaurantId={}, itemId={}", restaurantId, itemId);

        menuService.archiveItem(restaurantId, itemId);
    }
}
