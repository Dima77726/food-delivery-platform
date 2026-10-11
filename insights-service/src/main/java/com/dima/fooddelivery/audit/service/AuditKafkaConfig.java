package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.common.stores.StoreToggles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Фабрика слушателя сводного журнала: пакетная, как у аналитики, но с другим отношением
 * к отказу.
 *
 * <p><b>Без DLT и без предела попыток.</b> Аналитика после трёх неудач отправляет пачку
 * в топик разбора: строка отчёта, которую восстановят позже, — приемлемая потеря. У журнала
 * аудита отказ записи означает одно — недоступен ClickHouse, — и отправлять журнал в DLT
 * из-за этого значило бы разбирать руками каждую запись за время простоя. Поэтому
 * слушатель просто ждёт: повторяет ту же пачку каждые несколько секунд, пока ClickHouse
 * не вернётся, и офсет до тех пор не двигается. Записи не теряются — они лежат в топике,
 * а их оригиналы в журналах сервисов.
 *
 * <p>Неразбираемое сообщение до обработчика ошибок не доходит: слушатель его пропускает
 * сам (см. {@link AuditEventsConsumer}), иначе бесконечный повтор застрял бы на нём навсегда.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class AuditKafkaConfig {

    public static final String LISTENER_FACTORY = "auditBatchListenerContainerFactory";

    private static final long RETRY_INTERVAL_MILLIS = 5_000L;

    @Bean(LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> auditBatchListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new FixedBackOff(RETRY_INTERVAL_MILLIS, FixedBackOff.UNLIMITED_ATTEMPTS)
        );

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn(
                        "Сводный журнал аудита не записан, попытка {}: reason={}",
                        deliveryAttempt,
                        exception.getMessage()
                )
        );

        factory.setCommonErrorHandler(errorHandler);

        // Пакетный слушатель — пакетная фиксация офсета; см. AnalyticsKafkaConfig.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        return factory;
    }
}
