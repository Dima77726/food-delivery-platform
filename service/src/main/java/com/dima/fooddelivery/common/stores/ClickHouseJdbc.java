package com.dima.fooddelivery.common.stores;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Пул соединений к ClickHouse вместе с {@link JdbcTemplate} поверх него.
 *
 * <p><b>Зачем понадобилась обёртка вместо второго бина {@code DataSource}.</b> Автоконфигурация
 * Spring Boot создаёт основной {@code DataSource} только при условии, что бинов этого типа
 * в контексте ещё нет. Объявить второй {@code DataSource} — значит выключить первый: приложение
 * осталось бы без PostgreSQL, Liquibase и JPA, причём без единой ошибки на этапе сборки.
 *
 * <p>Обычный способ обойти это — объявить оба {@code DataSource} руками и пометить основной
 * как {@code @Primary}. Здесь он не подходит: настройки основного пула живут в application.yml
 * и подробно там объяснены, а ручное объявление пришлось бы держать с ними в синхронизации.
 *
 * <p>Обёртка решает задачу проще. Тип бина — {@code ClickHouseJdbc}, а не {@code DataSource},
 * поэтому автоконфигурация основного пула его не замечает. Взамен теряется автоматика: ни
 * метрик Hikari, ни health-индикатора, ни участия в транзакциях приложения. Первое и второе
 * при необходимости добавляются руками, третье нам и не нужно — ClickHouse про транзакции
 * не знает вовсе.
 */
public class ClickHouseJdbc implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ClickHouseJdbc(HikariDataSource dataSource, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        // Аналитический запрос по невезучему диапазону дат способен читать миллионы строк.
        // Без предела такой запрос держит соединение из маленького пула сколько угодно долго
        // и блокирует запись потребителя Kafka.
        this.jdbcTemplate.setQueryTimeout(queryTimeoutSeconds);
    }

    public JdbcTemplate template() {
        return jdbcTemplate;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
