package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * База для интеграционных тестов: настоящий PostgreSQL, настоящий SQL, настоящие миграции.
 *
 * <p>Все наследники делят один кэшированный контекст Spring и один контейнер, поэтому цена
 * второго и последующих тест-классов близка к нулю.
 *
 * <p>{@link Transactional} на классе означает откат после каждого теста — база не накапливает
 * мусор и тесты не зависят от порядка выполнения. Плата за это: метод сервиса с
 * {@code @Transactional} присоединится к транзакции теста, а не откроет свою, поэтому реальный
 * коммит здесь не проверяется. Для проверок, где важен именно коммит, нужен
 * {@code TestTransaction} или отдельный тест без этой аннотации.
 *
 * <p>Переопределения свойств заданы прямо здесь, а не файлом {@code src/test/resources/application.yml}:
 * такой файл не дополнил бы основной конфиг, а полностью заменил бы его — в classpath побеждает
 * первый файл с этим именем, и настройки datasource с Liquibase просто исчезли бы.
 */
@SpringBootTest(properties = {
        // Фоновая рассылка уведомлений в тестах не нужна: она лезет в базу посреди чужого
        // теста и шумит в логах ошибками отката.
        "app.notification.dispatch-enabled=false",
        "logging.level.liquibase=WARN",
        "logging.level.net.lbruun.springboot.preliquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestDataFactory testData;
}
