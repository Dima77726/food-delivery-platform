package com.dima.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Проверяет, что контекст собирается: все бины находят зависимости, нет циклов
 * и опечаток в конфигурации.
 *
 * <p>Datasource намеренно указывает на заведомо недоступный порт. Смысл не в том, чтобы
 * обойтись без базы ради скорости, а в том, чтобы тест доказывал утверждение: при старте
 * приложение не открывает соединение раньше, чем оно понадобится.
 *
 * <p>Без этого тест зависел бы от того, поднят ли у разработчика Postgres. Именно так и
 * случилось: {@code AdminBootstrap} с {@code @Transactional} открывал соединение при входе
 * в метод, локально оно устанавливалось и тест был зелёным, а на CI без базы контекст падал.
 */
@SpringBootTest(properties = {
        "spring.liquibase.enabled=false",
        "preliquibase.enabled=false",
        "spring.sql.init.mode=never",
        "app.notification.dispatch-enabled=false",
        "spring.datasource.url=jdbc:postgresql://localhost:1/unreachable-on-purpose"
})
class FoodDeliveryPlatformApplicationTests {

    @Test
    void contextLoads() {
    }

}
