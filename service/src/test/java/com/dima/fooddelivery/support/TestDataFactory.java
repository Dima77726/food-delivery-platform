package com.dima.fooddelivery.support;

import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.user.domain.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Собирает состояние базы для интеграционных тестов.
 *
 * <p>Данные вставляются напрямую через SQL, а не через публичные методы сервисов. Это сделано
 * намеренно: тест доставки должен падать из-за доставки, а не из-за того, что сломался
 * {@code CartService}. Подготовка данных не должна быть ещё одним тестируемым кодом.
 */
public class TestDataFactory {

    /**
     * Уникальные суффиксы для имён. Таблицы меню и категорий имеют unique-констрейнты по имени,
     * а откат транзакции не сбрасывает последовательности — поэтому счётчик, а не константа.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    /** BCrypt-хеш строки "password123". */
    public static final String KNOWN_PASSWORD = "password123";
    private static final String KNOWN_PASSWORD_HASH =
            "$2a$10$3euPcmQFCiblsZeEu5s7p.9OVHgeHWFwaAHubD8Wypx0GKF/hi5Ne";

    private final JdbcTemplate jdbcTemplate;

    public TestDataFactory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long uniqueSuffix() {
        return SEQUENCE.incrementAndGet();
    }

    /**
     * Пользователь с ролями. Пароль кладётся заранее посчитанным BCrypt-хешом строки
     * {@code "password123"} — считать хеш на каждый вызов дорого: BCrypt намеренно медленный,
     * и на сотне тестов это заметно.
     */
    public Long insertUser(String emailPrefix, UserRole... roles) {
        Long userId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO app_user (email, password_hash, full_name, phone)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                emailPrefix + "-" + uniqueSuffix() + "@example.test",
                KNOWN_PASSWORD_HASH,
                "Test User",
                "+70000000000"
        );

        for (UserRole role : roles) {
            jdbcTemplate.update(
                    "INSERT INTO app_user_role (user_id, role) VALUES (?, ?)",
                    userId,
                    role.name()
            );
        }

        return userId;
    }

    public Long insertCustomer() {
        return insertUser("customer", UserRole.CUSTOMER);
    }

    public Long insertCourier() {
        return insertUser("courier", UserRole.COURIER);
    }

    public Long insertRestaurant() {
        return insertRestaurant(true);
    }

    public Long insertRestaurant(boolean active) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO restaurant (name, description, city, is_active)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "Test Restaurant " + uniqueSuffix(),
                "Ресторан для интеграционных тестов",
                "Moscow",
                active
        );
    }

    public Long insertMenuCategory(Long restaurantId) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO menu_category (restaurant_id, name, sort_order)
                        VALUES (?, ?, 1)
                        RETURNING id
                        """,
                Long.class,
                restaurantId,
                "Test Category " + uniqueSuffix()
        );
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price) {
        return insertMenuItem(restaurantId, price, true);
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price, boolean available) {
        Long categoryId = insertMenuCategory(restaurantId);

        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO menu_item (category_id, name, description, price, is_available, sort_order)
                        VALUES (?, ?, ?, ?, ?, 1)
                        RETURNING id
                        """,
                Long.class,
                categoryId,
                "Test Item " + uniqueSuffix(),
                "Блюдо для интеграционных тестов",
                price,
                available
        );
    }

    public Long insertCart(Long customerId, Long restaurantId, String status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO cart (customer_id, restaurant_id, status)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                customerId,
                restaurantId,
                status
        );
    }

    public Long insertCartItem(Long cartId, Long menuItemId, int quantity, BigDecimal price) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO cart_item (cart_id, menu_item_id, quantity, price)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                cartId,
                menuItemId,
                quantity,
                price
        );
    }

    public Long insertOrder(Long cartId, Long customerId, Long restaurantId, OrderStatus status, BigDecimal totalAmount) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO customer_order (cart_id, customer_id, restaurant_id, status, total_amount)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                cartId,
                customerId,
                restaurantId,
                status.getDbValue(),
                totalAmount
        );
    }

    public Long insertOrderItem(Long orderId, Long menuItemId, int quantity, BigDecimal price) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO customer_order_item (
                            order_id, menu_item_id, menu_item_name, quantity, price, line_total
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                orderId,
                menuItemId,
                "Test Item",
                quantity,
                price,
                price.multiply(BigDecimal.valueOf(quantity))
        );
    }

    public Long insertDelivery(Long orderId, Long courierId, DeliveryStatus status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO delivery (order_id, courier_id, status)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                orderId,
                courierId,
                status.getDbValue()
        );
    }

    /**
     * Заказ со всей обязательной обвязкой: ресторан, блюдо, оформленная корзина, одна позиция.
     * Возвращает контекст, чтобы тест мог достать любой из созданных идентификаторов.
     */
    public OrderContext insertOrderInStatus(Long customerId, OrderStatus status) {
        BigDecimal price = new BigDecimal("450.00");

        Long restaurantId = insertRestaurant();
        Long menuItemId = insertMenuItem(restaurantId, price);
        Long cartId = insertCart(customerId, restaurantId, "CHECKED_OUT");
        Long orderId = insertOrder(cartId, customerId, restaurantId, status, price);
        Long orderItemId = insertOrderItem(orderId, menuItemId, 1, price);

        return new OrderContext(restaurantId, menuItemId, cartId, orderId, orderItemId, price);
    }

    public String currentOrderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?",
                String.class,
                orderId
        );
    }

    public String currentDeliveryStatus(Long deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM delivery WHERE id = ?",
                String.class,
                deliveryId
        );
    }

    public Integer countOrderEvents(Long orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_order_event WHERE order_id = ? AND event_type = ?",
                Integer.class,
                orderId,
                eventType
        );
    }

    public record OrderContext(
            Long restaurantId,
            Long menuItemId,
            Long cartId,
            Long orderId,
            Long orderItemId,
            BigDecimal price
    ) {
    }
}
