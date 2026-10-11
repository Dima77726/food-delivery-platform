package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * База для интеграционных тестов без дополнительных хранилищ: Kafka и двойники соседних
 * сервисов в памяти. Транзакций нет — основной базы у сервиса нет, откатывать нечего.
 */
@SpringBootTest(properties = {
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestDataFactory testData;

    @Autowired
    protected TestJwt jwt;
}
