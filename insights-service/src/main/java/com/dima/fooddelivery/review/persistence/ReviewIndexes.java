package com.dima.fooddelivery.review.persistence;

import com.dima.fooddelivery.common.stores.StoreToggles;
import com.dima.fooddelivery.review.domain.Review;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Индексы коллекции отзывов, создаваемые явно на старте.
 *
 * <p>У Spring Data MongoDB есть автоматический режим: аннотация {@code @Indexed} на поле
 * плюс {@code spring.data.mongodb.auto-index-creation=true}. Он здесь намеренно выключен,
 * и причин две.
 *
 * <p>Первая — момент создания. Автоматический режим создаёт индексы тогда, когда контекст
 * маппинга впервые встретит доменный тип, то есть на старте приложения и без всякой связи
 * с выключателем хранилища. При выключенном MongoDB приложение падало бы на старте, пытаясь
 * создать индекс в базе, которой нет.
 *
 * <p>Вторая — так честнее. Индекс в MongoDB — такая же часть схемы, как индекс в PostgreSQL,
 * и создаваться он должен явным шагом, который видно в коде и в логе. В PostgreSQL эту роль
 * играет Liquibase; здесь ту же работу делает этот класс.
 *
 * <p>{@code @PostConstruct}, а не событие готовности приложения: индексы обязаны существовать
 * до первого запроса. Если MongoDB недоступна, приложение упадёт здесь и сразу — это лучше,
 * чем стартовать с уникальным индексом, которого нет, и обнаружить два отзыва на один заказ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = StoreToggles.MONGO, havingValue = "true")
public class ReviewIndexes {

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    void createIndexes() {
        // Уникальный: один заказ — один отзыв. Это не оптимизация чтения, а ограничение
        // целостности. Проверка в сервисе перед вставкой его не заменяет: между проверкой
        // и записью помещается второй такой же запрос, и оба увидят, что отзыва ещё нет.
        mongoTemplate.indexOps(Review.class).createIndex(
                new Index().on("orderId", Sort.Direction.ASC).unique().named("uq_review_order")
        );

        // Составной под единственный тяжёлый запрос модуля: отзывы ресторана, свежие сверху.
        // Порядок полей важен — сначала то, по чему фильтруют, потом то, по чему сортируют.
        // В обратном порядке индекс перестал бы годиться для фильтра.
        mongoTemplate.indexOps(Review.class).createIndex(
                new Index()
                        .on("restaurantId", Sort.Direction.ASC)
                        .on("createdAt", Sort.Direction.DESC)
                        .named("ix_review_restaurant_created")
        );

        log.info("Индексы коллекции {} проверены", Review.COLLECTION);
    }
}
