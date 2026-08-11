package com.dima.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Быстрая проверка, что контекст вообще собирается: все бины находят свои зависимости,
 * нет циклов и опечаток в конфигурации.
 *
 * <p>База здесь не поднимается — миграции выключены, соединение не открывается. Это
 * сознательно: тест обязан отработать за секунду и не требовать Docker. Всё, что связано
 * с реальными запросами, проверяют классы {@code *IT}.
 */
@SpringBootTest(properties = {
        "spring.liquibase.enabled=false",
        "preliquibase.enabled=false",
        "spring.sql.init.mode=never",
        "app.notification.dispatch-enabled=false"
})
class FoodDeliveryPlatformApplicationTests {

    @Test
    void contextLoads() {
    }

}
