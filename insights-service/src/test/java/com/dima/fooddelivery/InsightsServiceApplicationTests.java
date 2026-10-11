package com.dima.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Проверяет, что контекст собирается без единого внешнего сервера: все бины находят
 * зависимости, нет циклов и опечаток в конфигурации.
 *
 * <p>Брокер и хранилища здесь не нужны: слушатели Kafka не стартуют, хранилища выключены
 * по умолчанию, а gRPC-каналы ленивые. Поднимись контекст только при живой Kafka — тест
 * зависел бы от того, что запущено у разработчика.
 */
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false"
})
class InsightsServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
