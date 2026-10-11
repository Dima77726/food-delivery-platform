package com.dima.fooddelivery.common.stores;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки модуля поиска.
 *
 * <p>Адрес самого Elasticsearch здесь не задаётся: его разбирает автоконфигурация Spring Boot
 * из {@code spring.elasticsearch.uris}. В отличие от Cassandra и Neo4j, стартер здесь взят —
 * он не создаёт ни менеджера транзакций, ни соединения на старте, а даёт готовый
 * {@code ElasticsearchOperations} с маппингом документов.
 *
 * @param enabled          включён ли модуль поиска
 * @param reindexInterval  как часто индекс перестраивается из PostgreSQL
 * @param reindexEnabled   запускать ли переиндексацию по расписанию
 * @param maxResults       потолок числа результатов в одном ответе
 */
@ConfigurationProperties(prefix = "app.stores.elasticsearch")
public record ElasticsearchStoreProperties(
        boolean enabled,
        Duration reindexInterval,
        Boolean reindexEnabled,
        Integer maxResults
) {

    public ElasticsearchStoreProperties {
        reindexInterval = reindexInterval == null ? Duration.ofMinutes(5) : reindexInterval;
        reindexEnabled = reindexEnabled == null ? Boolean.TRUE : reindexEnabled;
        maxResults = maxResults == null ? 50 : maxResults;
    }
}
