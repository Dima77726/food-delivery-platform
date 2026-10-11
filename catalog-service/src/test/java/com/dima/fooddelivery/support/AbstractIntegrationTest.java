package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * База для интеграционных тестов каталога: настоящие PostgreSQL и Redis, настоящие миграции.
 *
 * <p>{@link Transactional} на классе — откат после каждого теста. Redis откатом не чистится,
 * поэтому тесты кэша работают с уникальными ресторанами.
 */
@SpringBootTest(properties = {
        "logging.level.liquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestDataFactory testData;

    @Autowired
    protected TestJwt jwt;
}
