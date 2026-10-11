package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис каталога: рестораны, меню и поиск по витрине.
 *
 * <p>Класс лежит в корневом пакете намеренно: модули {@code restaurant}, {@code menu}
 * и {@code search} переехали из монолита со своими пакетами, и сканирование компонентов,
 * сущностей и репозиториев от корня находит их без единой правки в них самих.
 *
 * <p>{@code @EnableScheduling} нужен переиндексации поиска по расписанию.
 */
@EnableScheduling
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
