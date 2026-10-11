package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} нужен фоновым задачам: публикации outbox, проекции графа
 * рекомендаций, переиндексации поиска. Без этой аннотации {@code @Scheduled} молча
 * не запускается — события копились бы в outbox, и ничего бы не сломалось заметным образом.
 */
@EnableScheduling
@SpringBootApplication
public class FoodDeliveryPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodDeliveryPlatformApplication.class, args);
    }

}
