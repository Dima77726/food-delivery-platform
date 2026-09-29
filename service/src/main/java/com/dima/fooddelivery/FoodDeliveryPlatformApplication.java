package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} нужен модулю Notification: очередь уведомлений разбирает
 * периодическая задача. Без этой аннотации {@code @Scheduled} молча не запускается —
 * уведомления копились бы в статусе PENDING, и ничего бы не сломалось заметным образом.
 */
@EnableScheduling
@SpringBootApplication
public class FoodDeliveryPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodDeliveryPlatformApplication.class, args);
    }

}
