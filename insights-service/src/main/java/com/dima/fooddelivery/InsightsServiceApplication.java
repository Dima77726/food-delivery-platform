package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис отзывов, аналитики и рекомендаций — то, что осталось от монолита к последнему этапу
 * переезда. Наружу — прежний REST и GraphQL для экранов клиента.
 *
 * <p>{@code @EnableScheduling} нужен проекции графа рекомендаций: она догоняет заказы пачками
 * по расписанию. Без этой аннотации {@code @Scheduled} молча не запускается.
 */
@EnableScheduling
@SpringBootApplication
public class InsightsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightsServiceApplication.class, args);
    }
}
