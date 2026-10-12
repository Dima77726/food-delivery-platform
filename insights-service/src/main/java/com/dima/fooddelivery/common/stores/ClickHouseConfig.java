package com.dima.fooddelivery.common.stores;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Пул соединений к ClickHouse.
 *
 * <p>Стартера у ClickHouse нет, драйвер — обычный JDBC, поэтому пул строится руками: Hikari
 * с явными настройками и {@link ClickHouseJdbc} как тип бина. Почему именно обёртка, а не
 * второй {@code DataSource}, объяснено в самом {@link ClickHouseJdbc}.
 *
 * <p><b>Ленивая инициализация пула включена намеренно.</b> Hikari по умолчанию открывает
 * первое соединение при создании; здесь это откладывается до первого запроса. Смысл в том,
 * что схему в ClickHouse всё равно создаёт {@code OrderAnalyticsSchema} на старте, и если
 * сервер недоступен, ошибка должна прийти оттуда — с внятным сообщением про аналитику,
 * а не из недр создания бина.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseStoreProperties.class)
@ConditionalOnProperty(name = StoreToggles.CLICKHOUSE, havingValue = "true")
public class ClickHouseConfig {

    @Bean(destroyMethod = "close")
    ClickHouseJdbc clickHouseJdbc(ClickHouseStoreProperties properties) {
        HikariConfig config = new HikariConfig();

        config.setPoolName("clickhouse-pool");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3_000);
        // Пул поднимается при первом обращении, а не при создании бина.
        config.setInitializationFailTimeout(-1);
        // Автокоммит: транзакций в ClickHouse нет, и держать соединение в открытой
        // транзакции означало бы только копить состояние, которое некому зафиксировать.
        config.setAutoCommit(true);

        log.info("Пул ClickHouse настроен: url={}, poolSize={}", properties.url(), properties.poolSize());

        return new ClickHouseJdbc(
                new HikariDataSource(config),
                Math.toIntExact(properties.queryTimeout().toSeconds())
        );
    }
}
