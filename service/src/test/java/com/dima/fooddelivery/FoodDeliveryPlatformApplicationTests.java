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
        "app.outbox.publish-enabled=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        // Единственное исключение из правила выше, и оно осознанное: проверка схемы
        // Hibernate по определению требует соединения — сверить сущности с таблицами,
        // не прочитав таблиц, невозможно. Здесь Liquibase выключен и схемы нет вовсе,
        // так что валидировать всё равно нечего. В приложении ddl-auto остаётся validate.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:postgresql://localhost:1/unreachable-on-purpose"
})
class FoodDeliveryPlatformApplicationTests {

    @Test
    void contextLoads() {
    }

}
