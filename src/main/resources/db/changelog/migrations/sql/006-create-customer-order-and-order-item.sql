CREATE TABLE IF NOT EXISTS ${appSchema}.customer_order (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT not null,
    customer_id BIGINT not null,
    restaurant_id BIGINT not null,
    status VARCHAR(50) not null default 'CREATED',
    total_amount NUMERIC(12,2) not null,
    created_at timestamptz not null default CURRENT_TIMESTAMP,
    updated_at timestamptz not null default CURRENT_TIMESTAMP,

    constraint fk_customer_order_cart
                                                       foreign key (cart_id)
                                                       references ${appSchema}.cart (id)
                                                       on delete restrict,

    constraint fk_customer_order_restaurant
                                                       foreign key (restaurant_id)
                                                       references ${appSchema}.restaurant (id)
                                                       on DELETE restrict,

    constraint uq_customer_order_cart
                                                       unique (cart_id),

    constraint chk_customer_order_status
                                                       check ( status in (
                                                                          'CREATED',
                                                                         'PAID',
                                                                         'ACCEPTED',
                                                                         'COOKING',
                                                                         'READY_FOR_DELIVERY',
                                                                         'IN_DELIVERY',
                                                                         'DELIVERY',
                                                                         'CANCELED'
                                                           )
                                                       ),

    constraint chk_customer_order_total_amount_non_negative
                                                       check (total_amount >= 0)

);

comment on table ${appSchema}.customer_order is 'Заказ клиента, созданный из активной корзины';

comment on column ${appSchema}.customer_order.id is 'Уникальный идентификатор заказа';
comment on column ${appSchema}.customer_order.cart_id is 'Идентификатор корзины, из которой был создан заказ';
comment on column ${appSchema}.customer_order.customer_id is 'Идентификатор клиента, создавшего заказ';
comment on column ${appSchema}.customer_order.restaurant_id is 'Идентификатор ресторана, в котором создан заказ';
comment on column ${appSchema}.customer_order.status is 'Текущий статус заказа';
comment on column ${appSchema}.customer_order.total_amount is 'Итоговая сумма заказа';
comment on column ${appSchema}.customer_order.created_at is 'Дата и время создания заказа';
comment on column ${appSchema}.customer_order.updated_at is 'Дата и время последнего обновления заказа';

create index IF NOT EXISTS idx_customer_order_customer_id
on ${appSchema}.customer_order (customer_id);

create index if not exists idx_customer_order_restaurant_id
on ${appSchema}.customer_order (restaurant_id);

create index if not exists idx_customer_order_status
on ${appSchema}.customer_order (status);


create table if not exists ${appSchema}.customer_order_item (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT not null,
    menu_item_id BIGINT not null,
    menu_item_name VARCHAR(255) not null,
    quantity INTEGER not null,
    price NUMERIC(10,1) not null,
    line_total NUMERIC(12,2) not null,
    created_at timestamptz not null default CURRENT_TIMESTAMP,

    CONSTRAINT fk_customer_order_item_order
                                                            foreign key (order_id)
                                                            references ${appSchema}.customer_order (id)
                                                            on delete cascade,

    constraint fk_customer_order_item_menu_item
                                                            foreign key (menu_item_id)
                                                            references ${appSchema}.menu_item (id)
                                                            on DELETE restrict,

    constraint chk_customer_order_item_quantity_positive
                                                            check ( quantity > 0 ),

    constraint chk_customer_order_item_price_non_negative
                                                            check ( price >= 0 ),

    constraint chk_customer_order_item_line_total_non_negative
                                                            check ( price >= 0 ),

    constraint uq_customer_order_item_order_menu_item
                                                            unique (order_id, menu_item_id)
);

COMMENT ON TABLE ${appSchema}.customer_order_item IS 'Позиции заказа клиента';

COMMENT ON COLUMN ${appSchema}.customer_order_item.id IS 'Уникальный идентификатор позиции заказа';
COMMENT ON COLUMN ${appSchema}.customer_order_item.order_id IS 'Идентификатор заказа';
COMMENT ON COLUMN ${appSchema}.customer_order_item.menu_item_id IS 'Идентификатор блюда из меню';
COMMENT ON COLUMN ${appSchema}.customer_order_item.menu_item_name IS 'Название блюда на момент оформления заказа';
COMMENT ON COLUMN ${appSchema}.customer_order_item.quantity IS 'Количество единиц блюда';
COMMENT ON COLUMN ${appSchema}.customer_order_item.price IS 'Цена блюда на момент оформления заказа';
COMMENT ON COLUMN ${appSchema}.customer_order_item.line_total IS 'Стоимость позиции заказа: price * quantity';
COMMENT ON COLUMN ${appSchema}.customer_order_item.created_at IS 'Дата и время создания позиции заказа';

create index if not exists idx_customer_order_item_order_id
on ${appSchema}.customer_order_item (order_id);

create index if not exists idx_customer_order_item_menu_item_id
on ${appSchema}.customer_order_item (menu_item_id)