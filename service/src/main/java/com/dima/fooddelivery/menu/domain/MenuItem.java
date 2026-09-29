package com.dima.fooddelivery.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Блюдо в меню. Сущность Hibernate, часть агрегата {@link MenuCategory}.
 *
 * <p>Два разных признака «нет в продаже», и путать их нельзя:
 * <ul>
 *   <li>{@code available} — временно закончилось, вернётся завтра. Обратимо, управляется
 *       из зала одним переключателем;</li>
 *   <li>{@code archived} — убрано из меню навсегда. Строка остаётся в базе, потому что на неё
 *       ссылаются позиции старых заказов, но в меню больше не показывается.</li>
 * </ul>
 *
 * <p>Наружу этот класс не выходит: сервис отдаёт {@link MenuItemView} и
 * {@link MenuItemSnapshot}. Причина в {@code open-in-view: false} — за границей транзакции
 * ленивая связь {@link #category} уже недоступна, и любой её вызов дал бы
 * LazyInitializationException в контроллере.
 */
@Entity
@Table(name = "menu_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Владеющая сторона связи: колонка {@code category_id} лежит именно в этой таблице.
     *
     * <p>LAZY выбран осознанно, хотя для {@code @ManyToOne} по умолчанию стоит EAGER. С EAGER
     * каждое чтение блюда вытягивало бы ещё и категорию отдельным запросом, даже когда она
     * не нужна — а нужна она здесь почти никогда, потому что блюда читаются через саму
     * категорию.
     *
     * <p>Сеттера нет намеренно: блюдо не переезжает между категориями. Связь задаётся один раз
     * в конструкторе, который вызывает {@link MenuCategory#addItem}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MenuCategory category;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(length = 100)
    private String description;

    // precision и scale повторяют numeric(10, 2) из миграции. На поведение в рантайме они
    // не влияют — схему создаёт Liquibase, — но фиксируют ожидание: цена хранится
    // с двумя знаками, а не как double с плавающим хвостом.
    @Setter
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Setter
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Setter
    @Column(name = "is_available", nullable = false)
    private boolean available;

    // Сеттера нет: архивирование необратимо, и обратный путь не должен даже существовать
    // в API класса. Единственный способ изменить признак — метод archive().
    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Конструктор доступен только внутри пакета: блюдо создаётся через
     * {@link MenuCategory#addItem}, чтобы обе стороны связи всегда были согласованы.
     */
    MenuItem(MenuCategory category, String name, String description, BigDecimal price, int sortOrder) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        this.sortOrder = sortOrder;
        this.available = true;
    }

    /**
     * Идентификатор категории без загрузки самой категории.
     *
     * <p>Обращение к {@code getId()} на ленивом прокси не инициализирует его: значение
     * первичного ключа Hibernate знает и так, оно лежит в самом прокси. Любое другое поле
     * категории вызвало бы дополнительный SELECT.
     */
    public Long getCategoryId() {
        return category == null ? null : category.getId();
    }

    /**
     * Идемпотентно: повторный вызов ничего не меняет. Это важно для архивирования категории,
     * которое проходит по всем блюдам подряд, не разбирая, какие из них уже убраны.
     */
    public void archive() {
        this.archived = true;
    }

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

    @Override
    public boolean equals(Object other) {
        return other instanceof MenuItem that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return MenuItem.class.hashCode();
    }
}
