package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * База для тестов поиска: всё то же, что в {@link AbstractIntegrationTest}, плюс Elasticsearch.
 *
 * <p>Отдельный контекст, чтобы Elasticsearch — самый тяжёлый контейнер каталога — поднимался
 * только для тестов поиска, а не для каждого теста меню.
 *
 * <p>Откат после теста возвращает в исходное состояние PostgreSQL, но не индекс: тесты
 * работают с уникальными именами. Переиндексация по таймеру выключена — тест запускает её
 * сам и знает момент, когда она произошла.
 */
@SpringBootTest(properties = {
        "app.stores.elasticsearch.enabled=true",
        "app.stores.elasticsearch.reindex-enabled=false",
        "management.health.elasticsearch.enabled=true",
        "logging.level.liquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN",
        "logging.level.org.elasticsearch=WARN"
})
@Import({TestcontainersConfiguration.class, AbstractStoresIntegrationTest.ElasticsearchConfiguration.class})
@Transactional
public abstract class AbstractStoresIntegrationTest {

    @Autowired
    protected TestDataFactory testData;

    @TestConfiguration(proxyBeanMethods = false)
    static class ElasticsearchConfiguration {

        /**
         * Без xpack — как и в docker compose. С защитой контейнер поднимается по HTTPS
         * с самоподписанным сертификатом, и тесту пришлось бы возиться с truststore.
         */
        @Bean
        @ServiceConnection
        ElasticsearchContainer elasticsearchContainer() {
            return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.0")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
        }
    }
}
