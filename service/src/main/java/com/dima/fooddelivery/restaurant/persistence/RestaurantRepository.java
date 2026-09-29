package com.dima.fooddelivery.restaurant.persistence;

import com.dima.fooddelivery.restaurant.domain.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Репозиторий ресторанов на Spring Data JPA.
 *
 * <p>Реализации нет и не будет: Spring Data создаёт её сама в виде прокси на старте
 * приложения. {@link JpaRepository} приносит готовыми {@code findById}, {@code existsById},
 * {@code save}, {@code delete} и постраничные чтения — ровно то, что в JDBC-версии
 * этого репозитория занимало сотню строк рукописного SQL и RowMapper'ов.
 *
 * <p>Аннотация {@code @Repository} здесь не нужна: интерфейс находит сканер репозиториев
 * Spring Data, а не сканер компонентов. Перевод исключений драйвера в
 * {@code DataAccessException} тоже включён по умолчанию.
 *
 * <p>Сравнение с соседями по проекту:
 * {@code OrderRepository} — Spring JDBC, весь SQL написан руками;
 * {@code AuditLogRepository} — голый JDBC, руками написано ещё и получение соединения.
 *
 * @see com.dima.fooddelivery.restaurant.domain.Restaurant
 */
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    /**
     * Запрос выводится из имени метода: {@code findAll} + {@code OrderByIdAsc}.
     *
     * <p>Отдельный метод понадобился ради предсказуемого порядка. Унаследованный
     * {@code findAll()} не обещает никакого: в PostgreSQL строки без ORDER BY возвращаются
     * в порядке физического размещения, и после первого же UPDATE витрина перетасовалась бы.
     *
     * <p>Ошибка в имени метода — не опечатка в строке, а падение на старте контекста:
     * Spring Data не сможет разобрать имя и не создаст бин. В рукописном SQL такая же ошибка
     * дожила бы до первого вызова в проде.
     */
    List<Restaurant> findAllByOrderByIdAsc();
}
