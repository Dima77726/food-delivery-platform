package com.dima.fooddelivery.menu.persistence;

import com.dima.fooddelivery.menu.domain.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Категории меню на Spring Data JPA.
 *
 * <p>Здесь стоит сравнить с тем, что было: прежний {@code MenuRepository} на Spring JDBC
 * занимал 391 строку, из них шестьдесят — сборка плоского результата JOIN'а обратно в дерево
 * категорий с блюдами. Всё это делает один запрос ниже.
 */
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    /**
     * Всё меню ресторана одним запросом.
     *
     * <p>{@code LEFT JOIN FETCH} — это и есть решение проблемы N+1. Без {@code FETCH} Hibernate
     * прочитал бы категории, а затем на каждую из них сходил бы за блюдами отдельно: на меню
     * из десяти категорий получилось бы одиннадцать запросов вместо одного. Слово
     * {@code FETCH} отличается от обычного JOIN тем, что не просто соединяет таблицы
     * для условия, а сразу наполняет коллекцию в загруженных объектах.
     *
     * <p>{@code LEFT}, а не {@code INNER}: категория без блюд обязана попасть в результат.
     * Иначе только что созданная пустая категория исчезала бы из меню владельца.
     *
     * <p>{@code DISTINCT} не нужен, хотя в примерах для Hibernate 5 он встречается на каждом
     * шагу. База возвращает по строке на каждое блюдо, то есть категория дублируется, — но
     * начиная с Hibernate 6 дубликаты корневых сущностей отбрасываются сами. Добавлять
     * DISTINCT в SQL значило бы заставить PostgreSQL сортировать результат впустую.
     *
     * <p>Архивные строки не фильтруются намеренно, хотя витрине они не нужны. Фильтр внутри
     * {@code JOIN FETCH} наполнил бы коллекцию частично, и Hibernate считал бы такое
     * неполное состояние настоящим: при {@code orphanRemoval} недостающие блюда рискуют
     * быть понятыми как удалённые. Поэтому агрегат читается целиком, а лишнее отсекается
     * при сборке проекции — цена этого решения измеряется десятками строк на ресторан.
     */
    @Query("""
            SELECT c FROM MenuCategory c
            LEFT JOIN FETCH c.items
            WHERE c.restaurantId = :restaurantId
            ORDER BY c.sortOrder, c.id
            """)
    List<MenuCategory> findMenu(@Param("restaurantId") Long restaurantId);

    /**
     * Категория вместе с проверкой принадлежности ресторану — одним запросом.
     *
     * <p>Имя метода разворачивается в {@code WHERE id = ? AND restaurant_id = ?}. Проверять
     * принадлежность отдельным {@code exists}, как делала JDBC-версия, не нужно: пустой
     * результат уже означает «нет такой категории в этом ресторане». Это не микрооптимизация,
     * а защита — без условия по ресторану владелец одного заведения правил бы меню другого,
     * зная только идентификатор категории.
     */
    Optional<MenuCategory> findByIdAndRestaurantId(Long id, Long restaurantId);
}
