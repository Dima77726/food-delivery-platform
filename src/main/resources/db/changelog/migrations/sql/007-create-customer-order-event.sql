CREATE TABLE IF NOT EXISTS ${appSchema}.customer_order_event (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT not null,
    event_type VARCHAR(100) not null,
    description VARCHAR(500),
    created_at timestamptz not null default current_timestamp,

    constraint fk_customer_order_event_order
                                                             foreign key (order_id)
                                                             references ${appSchema}.customer_order  (id)
                                                             ON delete cascade,

    constraint chk_customer_order_event_type
                                                             check ( event_type in (
                                                                                    'ORDER_CREATED',
                                                                                    'ORDER_CANCELED'

                                                                                   )
                                                                 )
);

COMMENT ON table ${appSchema}.customer_order_event is 'История событий заказа клиента';

comment on column ${appSchema}.customer_order_event.id is 'Уникальный идентификатор события заказа';
comment on column ${appSchema}.customer_order_event.order_id  is 'Идентификатор заказа, к которому относится событие';
comment on column ${appSchema}.customer_order_event.event_type  is 'Тип события заказа';
comment on column ${appSchema}.customer_order_event.description  is 'Описание события заказа';
comment on column ${appSchema}.customer_order_event.created_at   is 'Дата и время создания события';

create index if not exists idx_customer_order_event_order_id
on ${appSchema}.customer_order_event (order_id);

create index if not exists idx_customer_order_event_event_type
on ${appSchema}.customer_order_event (event_type);

create index if not exists idx_customer_order_event_created_at
on ${appSchema}.customer_order_event (created_at);