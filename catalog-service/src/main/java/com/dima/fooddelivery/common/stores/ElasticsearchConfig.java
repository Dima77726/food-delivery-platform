package com.dima.fooddelivery.common.stores;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Настройки модуля поиска.
 *
 * <p>Бинов здесь нет, и это нормально: клиента Elasticsearch и {@code ElasticsearchOperations}
 * создаёт автоконфигурация Spring Boot по свойствам {@code spring.elasticsearch.*}. В отличие
 * от Cassandra и Neo4j отказываться от неё не пришлось — она не открывает соединение на старте
 * и не добавляет менеджера транзакций.
 *
 * <p>Класс существует ради одной строки: {@code @EnableConfigurationProperties}. Аннотация
 * {@code @ConfigurationProperties} сама по себе бина не создаёт, её нужно где-то включить,
 * и делать это безусловно неправильно — при выключенном поиске бин настроек висел бы
 * в контексте без единого потребителя.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ElasticsearchStoreProperties.class)
@ConditionalOnProperty(name = StoreToggles.ELASTICSEARCH, havingValue = "true")
public class ElasticsearchConfig {
}
