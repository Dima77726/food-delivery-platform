package com.dima.fooddelivery.menu.api;

import com.dima.fooddelivery.generated.api.MenuManagementApi;
import com.dima.fooddelivery.generated.model.CreateMenuCategoryRequest;
import com.dima.fooddelivery.generated.model.CreateMenuItemRequest;
import com.dima.fooddelivery.generated.model.ManagedMenu;
import com.dima.fooddelivery.generated.model.ManagedMenuCategory;
import com.dima.fooddelivery.generated.model.ManagedMenuItem;
import com.dima.fooddelivery.generated.model.UpdateMenuCategoryRequest;
import com.dima.fooddelivery.generated.model.UpdateMenuItemRequest;
import com.dima.fooddelivery.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Управление меню. Отдельный контроллер от {@link MenuController} не ради красоты:
 * тот отдаёт витрину анонимному посетителю, этот целиком закрыт проверкой управляющего.
 * Смешав их, легко однажды повесить публичный метод под общий {@code @PreAuthorize}
 * или, что хуже, наоборот.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("@access.managesRestaurant(#restaurantId)")
public class MenuManagementController implements MenuManagementApi {

    private final MenuService menuService;

    @Override
    public ResponseEntity<ManagedMenu> getManagedMenu(Long restaurantId) {
        return ResponseEntity.ok(MenuResponseMapper.toManagedResponse(menuService.getManagedMenu(restaurantId)));
    }

    // --- Категории ----------------------------------------------------------------------------

    @Override
    public ResponseEntity<ManagedMenuCategory> createMenuCategory(
            Long restaurantId,
            CreateMenuCategoryRequest request
    ) {
        log.info("Создание категории меню: restaurantId={}, name={}", restaurantId, request.getName());

        ManagedMenuCategory created = MenuResponseMapper.toManagedResponse(
                menuService.createCategory(restaurantId, request.getName(), request.getSortOrder())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    public ResponseEntity<ManagedMenuCategory> updateMenuCategory(
            Long restaurantId,
            Long categoryId,
            UpdateMenuCategoryRequest request
    ) {
        return ResponseEntity.ok(MenuResponseMapper.toManagedResponse(
                menuService.updateCategory(restaurantId, categoryId, request.getName(), request.getSortOrder())
        ));
    }

    /**
     * DELETE по смыслу, архивирование по факту: строки остаются в базе, потому что на них
     * ссылаются позиции прошлых заказов. Для клиента API это неотличимо от удаления.
     */
    @Override
    public ResponseEntity<Void> archiveMenuCategory(Long restaurantId, Long categoryId) {
        log.info("Архивирование категории: restaurantId={}, categoryId={}", restaurantId, categoryId);

        menuService.archiveCategory(restaurantId, categoryId);

        return ResponseEntity.noContent().build();
    }

    // --- Позиции ------------------------------------------------------------------------------

    @Override
    public ResponseEntity<ManagedMenuItem> createMenuItem(
            Long restaurantId,
            Long categoryId,
            CreateMenuItemRequest request
    ) {
        log.info("Создание блюда: restaurantId={}, categoryId={}, name={}",
                restaurantId, categoryId, request.getName());

        ManagedMenuItem created = MenuResponseMapper.toManagedResponse(menuService.createItem(
                restaurantId,
                categoryId,
                request.getName(),
                request.getDescription(),
                request.getPrice(),
                request.getSortOrder()
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    public ResponseEntity<ManagedMenuItem> updateMenuItem(
            Long restaurantId,
            Long itemId,
            UpdateMenuItemRequest request
    ) {
        return ResponseEntity.ok(MenuResponseMapper.toManagedResponse(menuService.updateItem(
                restaurantId,
                itemId,
                request.getName(),
                request.getDescription(),
                request.getPrice(),
                request.getSortOrder()
        )));
    }

    @Override
    public ResponseEntity<ManagedMenuItem> markMenuItemInStock(Long restaurantId, Long itemId) {
        return ResponseEntity.ok(MenuResponseMapper.toManagedResponse(
                menuService.setItemAvailability(restaurantId, itemId, true)
        ));
    }

    @Override
    public ResponseEntity<ManagedMenuItem> markMenuItemOutOfStock(Long restaurantId, Long itemId) {
        return ResponseEntity.ok(MenuResponseMapper.toManagedResponse(
                menuService.setItemAvailability(restaurantId, itemId, false)
        ));
    }

    @Override
    public ResponseEntity<Void> archiveMenuItem(Long restaurantId, Long itemId) {
        log.info("Архивирование блюда: restaurantId={}, itemId={}", restaurantId, itemId);

        menuService.archiveItem(restaurantId, itemId);

        return ResponseEntity.noContent().build();
    }
}
