package com.dima.fooddelivery.common.stores;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Соединение с Neo4j: голый драйвер вместо Spring Data Neo4j.
 *
 * <p><b>Почему не стартер.</b> Spring Data Neo4j объявляет собственный менеджер транзакций.
 * В приложении уже есть {@code JpaTransactionManager}, и второй бин того же типа делает
 * {@code @Transactional} без явного указания менеджера неоднозначным. Разбираться, какой
 * из них выберется в каждом конкретном методе, — не та задача, которую стоит заводить ради
 * трёх запросов Cypher.
 *
 * <p>Второй довод в пользу драйвера: рекомендации — это ровно те запросы, ради которых берут
 * графовую базу. Их интереснее видеть на Cypher целиком, чем спрятанными за производными
 * методами репозитория, где обход графа выглядит как обычный findBy.
 *
 * <p>Драйвер соединение при создании не открывает: {@code GraphDatabase.driver} только
 * готовит пул. Первое обращение к серверу произойдёт при первом запросе — или при вызове
 * {@code verifyConnectivity}, которого здесь намеренно нет. Проверку связи делает
 * {@code RecommendationGraph} на старте, когда создаёт ограничения: там ошибка сразу
 * объясняет, что именно сломалось.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Neo4jStoreProperties.class)
@ConditionalOnProperty(name = StoreToggles.NEO4J, havingValue = "true")
public class Neo4jConfig {

    /**
     * Драйвер, а не сессия: он потокобезопасен, держит пул соединений и живёт всё время
     * работы приложения. Сессия, наоборот, дешёвая и недолговечная — её открывают на запрос
     * и сразу закрывают.
     */
    @Bean(destroyMethod = "close")
    Driver neo4jDriver(Neo4jStoreProperties properties) {
        log.info("Драйвер Neo4j настроен: uri={}", properties.uri());

        return GraphDatabase.driver(
                properties.uri(),
                AuthTokens.basic(properties.username(), properties.password())
        );
    }
}
