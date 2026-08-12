package com.dima.fooddelivery.client;

import com.dima.fooddelivery.FoodDeliveryPlatformApplication;
import com.dima.fooddelivery.client.api.MenuCatalogApi;
import com.dima.fooddelivery.client.api.MenuManagementApi;
import com.dima.fooddelivery.client.api.RestaurantApi;
import com.dima.fooddelivery.client.model.CreateMenuCategoryRequest;
import com.dima.fooddelivery.client.model.CreateMenuItemRequest;
import com.dima.fooddelivery.client.model.CreateRestaurantRequest;
import com.dima.fooddelivery.client.model.ManagedMenuCategory;
import com.dima.fooddelivery.client.model.ManagedMenuItem;
import com.dima.fooddelivery.client.model.Restaurant;
import com.dima.fooddelivery.client.model.RestaurantMenu;
import com.dima.fooddelivery.client.model.UpdateRestaurantRequest;
import com.dima.fooddelivery.user.api.AuthTokenResponse;
import com.dima.fooddelivery.user.api.RegisterUserRequest;
import com.dima.fooddelivery.user.domain.UserRole;
import com.dima.fooddelivery.user.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контрактный тест: сгенерированный клиент разговаривает с настоящим приложением.
 *
 * <p>Зачем он нужен отдельно от тестов сервера. Компиляция клиента доказывает только то,
 * что YAML синтаксически корректен. Она не заметит, что сервер отдаёт поле под другим именем,
 * возвращает 200 вместо 201 или ждёт параметр в другом месте — всё это выяснится у потребителя.
 * Здесь обе стороны собраны из одной спеки и проверяются друг о друга.
 *
 * <p>Приложение поднимается на случайном порту в этом же JVM, но клиент ходит в него через
 * настоящий HTTP: сериализация, коды ответа и заголовки участвуют по-честному.
 *
 * <p>{@code @Transactional} здесь намеренно нет: сервер обрабатывает запрос в своём потоке
 * и в своей транзакции, откатить его работу из теста всё равно невозможно. Данные живут
 * до конца жизни контекста вместе с контейнером.
 */
@SpringBootTest(
        classes = FoodDeliveryPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.notification.dispatch-enabled=false",
                "logging.level.liquibase=WARN",
                "logging.level.org.testcontainers=WARN"
        }
)
@Import(ClientTestcontainersConfiguration.class)
class GeneratedClientContractIT {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthService authService;

    private ApiClient anonymousClient;
    private ApiClient ownerClient;

    @BeforeEach
    void setUp() {
        anonymousClient = new ApiClient().setBasePath("http://localhost:" + port);

        ownerClient = new ApiClient().setBasePath("http://localhost:" + port);
        ownerClient.setBearerToken(registerOwnerAndGetToken());
    }

    @Test
    void shouldCreateRestaurantThroughGeneratedClient() {
        Restaurant created = new RestaurantApi(ownerClient).createRestaurant(
                new CreateRestaurantRequest()
                        .name("Клиентская пиццерия")
                        .description("Создана через сгенерированный SDK")
                        .city("Москва")
        );

        assertAll(
                () -> assertEquals("Клиентская пиццерия", created.getName()),
                () -> assertEquals("Москва", created.getCity()),
                () -> assertTrue(created.getActive(), "новый ресторан сразу принимает заказы"),
                () -> assertTrue(created.getId() > 0)
        );
    }

    @Test
    void shouldReadStorefrontWithoutAuthentication() {
        Restaurant created = createRestaurant();

        List<Restaurant> restaurants = new RestaurantApi(anonymousClient).listRestaurants();
        Restaurant fetched = new RestaurantApi(anonymousClient).getRestaurant(created.getId());

        assertAll(
                () -> assertFalse(restaurants.isEmpty()),
                () -> assertEquals(created.getId(), fetched.getId()),
                () -> assertEquals(created.getName(), fetched.getName())
        );
    }

    /**
     * Деньги обязаны доехать без потерь. Именно ради этого в спеке цена объявлена как
     * bare number: с {@code format: double} генератор дал бы Double, и 450.00 могло бы
     * вернуться как 449.99999999999994.
     */
    @Test
    void shouldCarryMoneyAsExactDecimal() {
        Restaurant restaurant = createRestaurant();
        MenuManagementApi menuApi = new MenuManagementApi(ownerClient);

        ManagedMenuCategory category = menuApi.createMenuCategory(
                restaurant.getId(),
                new CreateMenuCategoryRequest().name("Пицца").sortOrder(1)
        );

        ManagedMenuItem item = menuApi.createMenuItem(
                restaurant.getId(),
                category.getId(),
                new CreateMenuItemRequest()
                        .name("Маргарита")
                        .description("Классика")
                        .price(new BigDecimal("450.55"))
                        .sortOrder(1)
        );

        RestaurantMenu publicMenu = new MenuCatalogApi(anonymousClient)
                .getRestaurantMenu(restaurant.getId());

        assertAll(
                () -> assertEquals(0, new BigDecimal("450.55").compareTo(item.getPrice())),
                () -> assertEquals(
                        0,
                        new BigDecimal("450.55").compareTo(
                                publicMenu.getCategories().get(0).getItems().get(0).getPrice()
                        ),
                        "цена должна дойти до витрины без искажения"
                )
        );
    }

    @Test
    void shouldSeeArchivedItemsOnlyInManagementView() {
        Restaurant restaurant = createRestaurant();
        MenuManagementApi menuApi = new MenuManagementApi(ownerClient);

        ManagedMenuCategory category = menuApi.createMenuCategory(
                restaurant.getId(),
                new CreateMenuCategoryRequest().name("Десерты")
        );

        ManagedMenuItem item = menuApi.createMenuItem(
                restaurant.getId(),
                category.getId(),
                new CreateMenuItemRequest().name("Тирамису").price(new BigDecimal("260.00"))
        );

        menuApi.archiveMenuItem(restaurant.getId(), item.getId());

        RestaurantMenu publicMenu = new MenuCatalogApi(anonymousClient)
                .getRestaurantMenu(restaurant.getId());
        var managedMenu = menuApi.getManagedMenu(restaurant.getId());

        assertAll(
                () -> assertTrue(publicMenu.getCategories().get(0).getItems().isEmpty()),
                () -> assertTrue(managedMenu.getCategories().get(0).getItems().get(0).getArchived())
        );
    }

    @Test
    void shouldSurfaceForbiddenAsHttpErrorForAnonymousCaller() {
        Restaurant restaurant = createRestaurant();

        // Анонимный клиент не имеет токена: сервер обязан ответить 401, и клиент
        // обязан превратить это в исключение, а не в пустой результат.
        HttpClientErrorException error = assertThrows(
                HttpClientErrorException.class,
                () -> new RestaurantApi(anonymousClient).updateRestaurant(
                        restaurant.getId(),
                        new UpdateRestaurantRequest().name("Захвачено")
                )
        );

        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void shouldCloseAndReopenRestaurantThroughClient() {
        Restaurant restaurant = createRestaurant();
        RestaurantApi api = new RestaurantApi(ownerClient);

        assertAll(
                () -> assertFalse(api.closeRestaurant(restaurant.getId()).getActive()),
                () -> assertTrue(api.openRestaurant(restaurant.getId()).getActive())
        );
    }

    private Restaurant createRestaurant() {
        return new RestaurantApi(ownerClient).createRestaurant(
                new CreateRestaurantRequest()
                        .name("Ресторан " + UUID.randomUUID())
                        .city("Москва")
        );
    }

    private String registerOwnerAndGetToken() {
        AuthTokenResponse response = authService.register(new RegisterUserRequest(
                "owner-" + UUID.randomUUID() + "@example.test",
                "password123",
                "Владелец ресторана",
                null,
                UserRole.RESTAURANT_OWNER
        ));

        return response.accessToken();
    }
}
