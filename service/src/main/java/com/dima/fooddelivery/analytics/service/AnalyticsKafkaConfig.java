package com.dima.fooddelivery.analytics.service;

import com.dima.fooddelivery.common.stores.StoreToggles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Отдельная фабрика слушателей — пакетная, только для аналитики.
 *
 * <p>Слушатель уведомлений читает топик по одному сообщению: уведомление осмысленно само
 * по себе, и обрабатывать их пачкой незачем. Аналитике нужно ровно обратное. Вставка
 * в ClickHouse по одной строке — антипаттерн (см. {@code OrderAnalyticsRepository}),
 * поэтому здесь сообщения забираются пачкой и уезжают в витрину одним INSERT.
 *
 * <p>Две фабрики уживаются потому, что слушатель выбирает нужную по имени бина в
 * {@code containerFactory}. Фабрика по умолчанию, созданная Spring Boot, остаётся
 * фабрикой по умолчанию, и слушатель уведомлений её не теряет.
 *
 * <p><b>AckMode.BATCH обязателен.</b> В application.yml задан режим RECORD — офсет
 * фиксируется после каждого сообщения. С пакетным слушателем такой режим невозможен
 * технически: фиксировать нечего, пока не обработана вся пачка. Spring это заметит
 * и откажется стартовать - лучше так, чем молчаливое расхождение с настройкой.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AnalyticsKafkaConfig {

    public static final String LISTENER_FACTORY = "analyticsBatchListenerContainerFactory";

    @Bean(LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> analyticsBatchListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);

        // Обработчик ошибок общий с остальным приложением: три попытки, потом DLT.
        // Для пакетного слушателя это означает повтор всей пачки целиком, а в DLT уезжает
        // тоже вся пачка. Точечный разбор потребовал бы BatchListenerFailedException
        // с указанием сбойной записи; здесь он не нужен, потому что сам слушатель
        // неразбираемые сообщения пропускает и исключений из-за них не бросает.
        factory.setCommonErrorHandler(errorHandler);

        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        return factory;
    }
}
