create table if not exists ${appSchema}.cart (
    id bigserial primary key ,
    customer_id bigint not null,
    restaurant_id bigint not null,
    status VARCHAR(50) not null default 'ACTIVE',
    created_at timestamptz not null default CURRENT_TIMESTAMP,
    updated_at timestamptz not null default CURRENT_TIMESTAMP,

    constraint fk_cart_restaurant
                                             foreign key (restaurant_id)
                                             references ${appSchema}.restaurant (id)
                                             on delete cascade,

    constraint chk_cart_status
                                             check ( status in ('ACTIVE', 'CHECKED_OUT', 'CANCELED', 'ABANDONED'))
);

comment on table ${appSchema}.cart is 'Корзина пользователя для выбранного ресторана';

comment on column ${appSchema}.cart.id is 'Уникальный идентификатор корзины';
comment on column ${appSchema}.cart.customer_id is 'Идентификатор пользователя, которому принадлежит корзина';
comment on column ${appSchema}.cart.restaurant_id is 'Идентификатор ресторана, к которому относится корзина';
comment on column ${appSchema}.cart.status is 'Текущий статус корзины';
comment on column ${appSchema}.cart.created_at is 'Дата и время создания корзины';
comment on column ${appSchema}.cart.updated_at is 'Дата и время последнего обновления корзины';

create index if not exists idx_cart_customer_id
on ${appSchema}.cart (customer_id);

create index if not exists idx_cart_restaurant_id
on ${appSchema}.cart (restaurant_id);

create index if not exists idx_cart_status
on ${appSchema}.cart (status);

create unique index if not exists uq_cart_active_customer_restaurant
on ${appSchema}.cart (customer_id, restaurant_id)
where status = 'ACTIVE';

create table if not exists ${appSchema}.cart_item (
    id bigserial primary key,
    cart_id bigint not null,
    menu_item_id bigint not null,
    quantity integer not null,
    price numeric(10, 2) not null,
    created_at timestamptz not null default CURRENT_TIMESTAMP,
    updated_at timestamptz not null default CURRENT_TIMESTAMP,

    constraint fk_cart_item_menu_item
                                                  foreign key (menu_item_id)
                                                  references ${appSchema}.menu_item (id)
                                                  on delete restrict,

    constraint fk_cart_item_cart
                                                  foreign key (cart_id)
                                                  references ${appSchema}.cart (id)
                                                  on delete cascade,

    constraint chk_cart_item_quantity_pozitive
                                                  check ( quantity > 0 ),

    constraint chk_cart_item_price_non_negative
                                                  check ( price >= 0 ),

    constraint uq_cart_item_cart_menu_item
        unique (cart_id, menu_item_id)
);

COMMENT ON TABLE ${appSchema}.cart_item IS 'Позиции, добавленные пользователем в корзину';

COMMENT ON COLUMN ${appSchema}.cart_item.id IS 'Уникальный идентификатор позиции корзины';
COMMENT ON COLUMN ${appSchema}.cart_item.cart_id IS 'Идентификатор корзины, к которой относится позиция';
COMMENT ON COLUMN ${appSchema}.cart_item.menu_item_id IS 'Идентификатор блюда, добавленного в корзину';
COMMENT ON COLUMN ${appSchema}.cart_item.quantity IS 'Количество выбранных единиц блюда';
COMMENT ON COLUMN ${appSchema}.cart_item.price IS 'Цена блюда на момент добавления в корзину';
COMMENT ON COLUMN ${appSchema}.cart_item.created_at IS 'Дата и время создания позиции корзины';
COMMENT ON COLUMN ${appSchema}.cart_item.updated_at IS 'Дата и время последнего обновления позиции корзины';

create index idx_cart_item_cart_id
on ${appSchema}.cart_item (cart_id);

create index idx_cart_item_menu_item_id
on ${appSchema}.cart_item (menu_item_id);