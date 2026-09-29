package com.dima.fooddelivery.menu.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Категория меню и корень агрегата «категория с блюдами».
 *
 * <p>Здесь видно то, ради чего JPA вообще берут: связь {@code @OneToMany} собирает дерево
 * объектов сама. В JDBC-версии этого модуля ту же работу делали шестьдесят строк ручной
 * сборки — плоские строки JOIN'а складывались в {@code LinkedHashMap} и разбирались обратно
 * по категориям. Весь этот код удалён; его заменил один запрос с {@code LEFT JOIN FETCH}.
 */
@Entity
@Table(name = "menu_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Ссылка на ресторан хранится идентификатором, а не связью {@code @ManyToOne}, и это
     * не упрощение.
     *
     * <p>Ресторан — соседний модуль со своим агрегатом. Связь на уровне Hibernate позволила бы
     * из меню пройти в ресторан и дальше по всему графу, и граница между модулями,
     * которую в этом проекте держат руками, перестала бы существовать. Внутри одного
     * агрегата связи есть — см. {@link #items}; через границу модуля идёт только идентификатор.
     */
    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Блюда категории.
     *
     * <p>{@code mappedBy} говорит, что владеющая сторона — {@link MenuItem#getCategory()},
     * то есть колонка {@code category_id} в таблице блюд. Без него Hibernate решил бы, что
     * связь хранится в отдельной таблице-связке, и потребовал бы её при валидации схемы.
     *
     * <p>{@code cascade = ALL} позволяет сохранить новое блюдо, просто добавив его в этот
     * список: отдельный вызов save для блюда не нужен. {@code orphanRemoval} доводит мысль
     * до конца — блюдо, убранное из списка, физически удаляется. В этом модуле удаления
     * не происходит никогда (используется архивирование), но настройка описывает
     * правило владения: блюдо не существует само по себе, вне категории.
     *
     * <p>Загрузка ленивая — это поведение {@code @OneToMany} по умолчанию, и менять его
     * не нужно. Там, где блюда действительно нужны, они подгружаются одним запросом через
     * {@code LEFT JOIN FETCH}; EAGER же тянул бы их всегда, включая случаи вроде проверки
     * принадлежности категории ресторану.
     */
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<MenuItem> items = new ArrayList<>();

    public MenuCategory(Long restaurantId, String name, int sortOrder) {
        this.restaurantId = restaurantId;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    /**
     * Единственный способ завести блюдо: обе стороны связи проставляются здесь.
     *
     * <p>Если добавить блюдо только в список, не задав ему категорию, Hibernate при
     * {@code mappedBy} посмотрит именно на сторону блюда — и запишет NULL в {@code category_id}.
     * Ровно поэтому конструктор {@link MenuItem} закрыт границами пакета.
     */
    public MenuItem addItem(String itemName, String description, BigDecimal price, int itemSortOrder) {
        MenuItem item = new MenuItem(this, itemName, description, price, itemSortOrder);

        items.add(item);

        return item;
    }

    /**
     * Убирает категорию из меню вместе со всеми её блюдами.
     *
     * <p>Обращение к {@link #items} инициализирует коллекцию, то есть выполняет SELECT.
     * Это осознанная цена: список блюд нужен, чтобы пройти по нему, а количество блюд
     * в одной категории измеряется десятками. JDBC-версия обходилась одним UPDATE по
     * {@code category_id} без чтения — быстрее, но и правило «архив категории уносит блюда»
     * было размазано по двум SQL-запросам вместо одного метода.
     */
    public void archive() {
        this.archived = true;

        items.forEach(MenuItem::archive);
    }

    /**
     * Список только для чтения: менять состав блюд можно лишь через {@link #addItem}.
     * Иначе внешний код мог бы очистить коллекцию, и {@code orphanRemoval} удалил бы
     * строки, на которые ссылаются позиции старых заказов.
     */
    public List<MenuItem> getItems() {
        return Collections.unmodifiableList(items);
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
        return other instanceof MenuCategory that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return MenuCategory.class.hashCode();
    }
}
