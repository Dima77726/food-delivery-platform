package com.dima.fooddelivery.user.api;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет цепочку фильтров Spring Security через HTTP.
 *
 * <p>Тесты сервисов сюда не достают: {@code @PreAuthorize} висит на контроллерах, и вызов
 * сервиса напрямую его не задействует. Поэтому реальную защиту эндпоинтов можно проверить
 * только запросом.
 */
@AutoConfigureMockMvc
class AuthApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRegisterAndReturnToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(uniqueEmail(), "CUSTOMER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.roles[0]").value("CUSTOMER"))
                // Хеш пароля не должен попасть в ответ ни при каких обстоятельствах.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void shouldRejectSelfRegistrationAsAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(uniqueEmail(), "ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRejectLoginWithWrongPassword() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(email, "CUSTOMER")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrong-password"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnauthenticatedAccessToProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowAnonymousAccessToRestaurantList() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldForbidReadingForeignOrders() throws Exception {
        String token = registerAndGetToken(uniqueEmail());
        Long foreignCustomerId = testData.insertCustomer();

        // Роль CUSTOMER у пользователя есть, но идентификатор в пути чужой.
        // Без сверки с токеном это была бы дыра, через которую видны чужие заказы.
        mockMvc.perform(get("/api/v1/customers/{id}/orders", foreignCustomerId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldForbidCustomerFromReachingAdminApi() throws Exception {
        String token = registerAndGetToken(uniqueEmail());

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowReadingOwnOrders() throws Exception {
        String email = uniqueEmail();
        String token = registerAndGetToken(email);
        long userId = userIdFromToken(token);

        mockMvc.perform(get("/api/v1/customers/{id}/orders", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String registerAndGetToken(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(email, "CUSTOMER")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.accessToken");
    }

    private long userIdFromToken(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String registrationJson(String email, String role) {
        return """
                {
                  "email": "%s",
                  "password": "password123",
                  "fullName": "Тестовый Пользователь",
                  "phone": "+70000000000",
                  "role": "%s"
                }
                """.formatted(email, role);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
