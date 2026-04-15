CREATE table if not exists ${appSchema}.menu_category (
    id bigserial primary key,
    restaurant_id bigint not null,
    name varchar(255) not null,
    sort_order integer not null default 0,
    created_at timestamptz not null default current_timestamp,

    constraint fk_menu_category_restaurant
      foreign key (restaurant_id)
          references ${appSchema}.restaurant (id)
          on delete cascade,

    constraint uq_menu_category_restaurant_name
        unique (restaurant_id, name)

);

comment on table ${appSchema}.menu_category is 'Категория меню ресторана';
comment on column ${appSchema}.menu_category.id is 'Уникальный идентификатор категории меню';
comment on column ${appSchema}.menu_category.restaurant_id is 'Идентификатор ресторана, которому принадлежит категория';
comment on column ${appSchema}.menu_category.name is 'Название категории меню';
comment on column ${appSchema}.menu_category.sort_order is 'Порядок отображения категории в меню ресторана';
comment on column ${appSchema}.menu_category.created_at is 'Дата и время создания записи';

create index if not exists idx_menu_category_restaurant_id
on ${appSchema}.menu_category (restaurant_id);

create table if not exists ${appSchema}.menu_item
(
    id           bigserial primary key,
    category_id  bigint         not null,
    name         varchar(255)   not null,
    description  varchar(100),
    price        numeric(10, 2) not null,
    is_available boolean        not null default true,
    sort_order   integer        not null default 0,
    created_at   timestamptz    not null default current_timestamp,

    constraint fk_menu_item_category
        foreign key (category_id)
            references ${appSchema}.menu_category (id)
            on delete cascade,

    constraint uq_menu_item_category_name
        unique (category_id, name),

    constraint chk_menu_item_price_positive
        check ( price >= 0)

);

comment on table ${appSchema}.menu_item is 'Позиции меню ресторана';
comment on column ${appSchema}.menu_item.id is 'Уникальный идентификатор позиции меню';
comment on column ${appSchema}.menu_item.category_id is 'Идентификатор категории меню, к которой относится позиция';
comment on column ${appSchema}.menu_item.name is 'Название блюда или напитка';
comment on column ${appSchema}.menu_item.description is 'Текстовое описание позиции меню';
comment on column ${appSchema}.menu_item.price is 'Цена позиции меню';
comment on column ${appSchema}.menu_item.is_available is 'Признак доступности позиции меню для заказа';
comment on column ${appSchema}.menu_item.sort_order is 'Порядок отображения позиции внутри категории';
comment on column ${appSchema}.menu_item.created_at is 'Дата и время создания записи';

create index if not exists idx_menu_item_category_id
on ${appSchema}.menu_item (category_id);

create index if not exists idx_menu_item_is_available
on ${appSchema}.menu_item (is_available);

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Pizza', 1
FROM ${appSchema}.restaurant r
WHERE r.name = 'Pizza House'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Drinks', 2
FROM ${appSchema}.restaurant r
WHERE r.name = 'Pizza House'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Desserts', 3
FROM ${appSchema}.restaurant r
WHERE r.name = 'Pizza House'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Burgers', 1
FROM ${appSchema}.restaurant r
WHERE r.name = 'Burger Point'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Snacks', 2
FROM ${appSchema}.restaurant r
WHERE r.name = 'Burger Point'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Drinks', 3
FROM ${appSchema}.restaurant r
WHERE r.name = 'Burger Point'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Rolls', 1
FROM ${appSchema}.restaurant r
WHERE r.name = 'Sushi Time'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Sets', 2
FROM ${appSchema}.restaurant r
WHERE r.name = 'Sushi Time'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_category (restaurant_id, name, sort_order)
SELECT r.id, 'Drinks', 3
FROM ${appSchema}.restaurant r
WHERE r.name = 'Sushi Time'
ON CONFLICT (restaurant_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Margherita', 'Classic pizza with tomato sauce and mozzarella', 450.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Pizza House' AND mc.name = 'Pizza'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Pepperoni', 'Pizza with pepperoni and mozzarella', 520.00, TRUE, 2
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Pizza House' AND mc.name = 'Pizza'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Cola', 'Classic cold drink 0.5L', 120.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Pizza House' AND mc.name = 'Drinks'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Tiramisu', 'Italian dessert with mascarpone', 260.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Pizza House' AND mc.name = 'Desserts'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Cheeseburger', 'Burger with beef patty and cheddar cheese', 390.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Burger Point' AND mc.name = 'Burgers'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Double Burger', 'Burger with double beef patty', 520.00, TRUE, 2
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Burger Point' AND mc.name = 'Burgers'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'French Fries', 'Crispy potato fries', 180.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Burger Point' AND mc.name = 'Snacks'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Lemonade', 'House lemonade 0.4L', 150.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Burger Point' AND mc.name = 'Drinks'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Philadelphia Roll', 'Roll with salmon and cream cheese', 430.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Sushi Time' AND mc.name = 'Rolls'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'California Roll', 'Roll with crab mix and cucumber', 410.00, TRUE, 2
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Sushi Time' AND mc.name = 'Rolls'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Family Set', 'Large sushi set for company', 1250.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Sushi Time' AND mc.name = 'Sets'
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO ${appSchema}.menu_item (category_id, name, description, price, is_available, sort_order)
SELECT mc.id, 'Green Tea', 'Hot green tea', 110.00, TRUE, 1
FROM ${appSchema}.menu_category mc
         JOIN ${appSchema}.restaurant r ON r.id = mc.restaurant_id
WHERE r.name = 'Sushi Time' AND mc.name = 'Drinks'
ON CONFLICT (category_id, name) DO NOTHING;