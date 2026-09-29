package com.dima.fooddelivery.review.persistence;

import com.dima.fooddelivery.review.domain.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий отзывов на Spring Data MongoDB.
 *
 * <p>Интерфейс тот же по духу, что и {@code RestaurantRepository} на JPA: реализацию создаёт
 * Spring Data, запросы выводятся из имён методов. Разница — в том, во что они выводятся:
 * не в SQL, а в фильтр BSON, который уходит в find.
 *
 * <p>Почему это не мешает JPA, живущей в том же приложении: Spring Data в строгом режиме
 * раздаёт интерфейсы модулям по базовому типу. Этот наследует {@link MongoRepository},
 * значит принадлежит модулю MongoDB, и сканер JPA его не тронет. Ошибиться здесь можно
 * ровно одним способом — унаследовав общий {@code CrudRepository}: тогда владельца
 * пришлось бы угадывать по аннотациям доменного типа.
 *
 * <p>Бин создаётся всегда, даже при выключенном хранилище: создание не открывает соединения.
 * Обращаются к нему только компоненты, помеченные {@code @ConditionalOnProperty} — их
 * при выключенном MongoDB просто нет.
 */
public interface ReviewRepository extends MongoRepository<Review, String> {

    /**
     * Отзывы ресторана порцией. {@link Pageable} даёт и сортировку, и skip с limit.
     *
     * <p>Возвращается {@code List}, а не {@code Page}: {@code Page} потянул бы за собой
     * дополнительный count на каждый запрос, а он в MongoDB по фильтру стоит примерно
     * столько же, сколько сама выборка. Общее число берётся отдельным методом и только там,
     * где оно действительно нужно.
     */
    List<Review> findByRestaurantId(Long restaurantId, Pageable pageable);

    long countByRestaurantId(Long restaurantId);

    Optional<Review> findByOrderId(Long orderId);
}
