package com.dima.fooddelivery.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Ресторан. Сущность Hibernate.
 *
 * <p>Здесь модуль намеренно отличается от остальных: {@code order}, {@code cart} и
 * {@code delivery} работают через Spring JDBC с рукописным SQL, {@code audit} — через голый
 * JDBC, а {@code restaurant} и {@code menu} переведены на Spring Data JPA. Смысл разнобоя
 * в том, чтобы разница подходов была видна на одной кодовой базе, а не пересказывалась
 * словами. Все они живут в одной транзакции и на одном DataSource.
 *
 * <p>Record сущностью быть не может: Hibernate обязан создать объект без аргументов, а затем
 * заполнить поля — на неизменяемом типе это невозможно. Отсюда изменяемый класс,
 * конструктор без аргументов и сеттеры.
 */
@Entity
@Table(name = "restaurant")
@Getter
@Setter
// Конструктор нужен Hibernate, но не прикладному коду: создавать ресторан без названия
// и города бессмысленно. protected закрывает его от вызова снаружи, а Hibernate
// добирается до него через reflection и не замечает разницы.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant {

    /**
     * IDENTITY, потому что колонка объявлена как BIGSERIAL: значение генерирует сама база,
     * а Hibernate забирает его через {@code getGeneratedKeys()}.
     *
     * <p>У этой стратегии есть цена, о которой стоит знать: пакетная вставка с ней
     * не работает. Hibernate обязан выполнить INSERT немедленно, чтобы узнать
     * идентификатор, и {@code hibernate.jdbc.batch_size} на такие сущности не влияет.
     * Для ресторанов это безразлично — их создают поштучно; там, где вставки массовые,
     * пришлось бы брать SEQUENCE с пулом идентификаторов.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private String city;

    // Имя поля и имя колонки разошлись: в базе принят префикс is_ для булевых признаков,
    // в Java он лишний. @Column — единственное место, где это расхождение зафиксировано.
    @Column(name = "is_active", nullable = false)
    private boolean active;

    // updatable = false: колонка заполняется один раз при вставке. Без этого флага Hibernate
    // включал бы её в каждый UPDATE и дата создания переписывалась бы при любой правке.
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Restaurant(String name, String description, String city) {
        this.name = name;
        this.description = description;
        this.city = city;
        // Новый ресторан сразу принимает заказы. В базе у колонки тот же DEFAULT TRUE,
        // но полагаться на него нельзя: Hibernate всегда пишет все колонки явно,
        // и DEFAULT в INSERT просто не участвует.
        this.active = true;
    }

    /**
     * Отметки времени проставляет Hibernate, а не {@code DEFAULT CURRENT_TIMESTAMP} в базе.
     *
     * <p>Так сделано потому, что при UPDATE база сама ничего не обновит — для этого
     * понадобился бы триггер. Раз момент изменения всё равно приходится проставлять из кода,
     * логичнее держать оба поля в одном месте, а не половину в Java и половину в DDL.
     */
    @PrePersist
    void onInsert() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Равенство по идентификатору, и только по нему.
     *
     * <p>Сгенерированные Lombok {@code @EqualsAndHashCode} здесь были бы ошибкой: они
     * сравнивают все поля, а у сущности поля меняются в течение её жизни. Объект, положенный
     * в HashSet до сохранения, перестал бы находиться в нём после того, как Hibernate
     * проставит id.
     *
     * <p>{@code instanceof}, а не {@code getClass() ==}: для ленивой связи Hibernate
     * подставляет прокси-наследник, и сравнение классов на нём всегда давало бы false.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        return other instanceof Restaurant that && id != null && id.equals(that.id);
    }

    /**
     * Константа намеренно.
     *
     * <p>hashCode обязан не меняться, пока объект лежит в хэш-коллекции. У новой сущности
     * id ещё null, а после сохранения он появляется — хэш по id менялся бы прямо в контейнере,
     * и объект в нём терялся бы. Все рестораны в одной корзине это небольшая плата
     * за корректность.
     */
    @Override
    public int hashCode() {
        return Restaurant.class.hashCode();
    }
}
