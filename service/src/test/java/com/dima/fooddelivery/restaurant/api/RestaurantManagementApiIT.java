package com.dima.fooddelivery.restaurant.api;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет права на управление рестораном через HTTP.
 *
 * <p>Сервисные тесты сюда не достают: {@code @PreAuthorize} висит на контроллерах, и прямой
 * вызов сервиса его не задействует. При этом именно здесь проходит граница между «мой ресторан»
 * и «чужой» — самое дорогое место, если ошибиться.
 */
@AutoConfigureMockMvc
class RestaurantManagementApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateRestaurantAndMakeCreatorItsManager() throws Exception {
        String ownerToken = registerAndGetToken("RESTAURANT_OWNER");

        String body = mockMvc.perform(post("/api/v1/restaurants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Тестовая пиццерия", "description": "Тесто и сыр", "city": "Москва"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Тестовая пиццерия"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();

        long restaurantId = ((Number) JsonPath.read(body, "$.id")).longValue();

        // Создатель обязан сразу получить права: иначе ресторан остался бы без хозяина.
        mockMvc.perform(patch("/api/v1/restaurants/{id}", restaurantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "Обновлённое описание"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Обновлённое описание"))
                .andExpect(jsonPath("$.name").value("Тестовая пиццерия"));
    }

    @Test
    void shouldNotLetCustomerCreateRestaurant() throws Exception {
        String customerToken = registerAndGetToken("CUSTOMER");

        mockMvc.perform(post("/api/v1/restaurants")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Не мой ресторан", "city": "Москва"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldNotLetForeignOwnerTouchAnotherRestaurant() throws Exception {
        String ownerToken = registerAndGetToken("RESTAURANT_OWNER");
        String foreignOwnerToken = registerAndGetToken("RESTAURANT_OWNER");

        long restaurantId = createRestaurant(ownerToken);

        mockMvc.perform(patch("/api/v1/restaurants/{id}", restaurantId)
                        .header("Authorization", "Bearer " + foreignOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Захвачено"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/restaurants/{id}/menu/categories", restaurantId)
                        .header("Authorization", "Bearer " + foreignOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Чужая категория"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldKeepPublicMenuOpenAndManagementClosed() throws Exception {
        String ownerToken = registerAndGetToken("RESTAURANT_OWNER");
        long restaurantId = createRestaurant(ownerToken);

        // Витрина открыта анонимно — ради неё пользователь и приходит.
        mockMvc.perform(get("/api/v1/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isOk());

        // Управление меню лежит на соседнем пути и под витрину не подпадает.
        mockMvc.perform(get("/api/v1/restaurants/{id}/menu/manage", restaurantId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/restaurants/{id}/menu/manage", restaurantId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCloseAndReopenRestaurant() throws Exception {
        String ownerToken = registerAndGetToken("RESTAURANT_OWNER");
        long restaurantId = createRestaurant(ownerToken);

        mockMvc.perform(post("/api/v1/restaurants/{id}/close", restaurantId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/v1/restaurants/{id}/open", restaurantId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void shouldRejectInvalidMenuItemPrice() throws Exception {
        String ownerToken = registerAndGetToken("RESTAURANT_OWNER");
        long restaurantId = createRestaurant(ownerToken);

        String categoryBody = mockMvc.perform(post("/api/v1/restaurants/{id}/menu/categories", restaurantId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Напитки", "sortOrder": 1}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long categoryId = ((Number) JsonPath.read(categoryBody, "$.id")).longValue();

        mockMvc.perform(post("/api/v1/restaurants/{r}/menu/categories/{c}/items", restaurantId, categoryId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Кола", "price": -100.00}
                                """))
                .andExpect(status().isBadRequest());
    }

    private long createRestaurant(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ресторан %s", "city": "Москва"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String registerAndGetToken(String role) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user-%s@example.test",
                                  "password": "password123",
                                  "fullName": "Тестовый Пользователь",
                                  "role": "%s"
                                }
                                """.formatted(UUID.randomUUID(), role)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.accessToken");
    }
}
