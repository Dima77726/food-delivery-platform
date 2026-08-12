CREATE TABLE if not exists ${appSchema}.delivery
(
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGSERIAL   NOT NULL,
    courier_id   BIGINT,
    status       VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    picked_up_at timestamptz,
    delivered_at timestamptz,

    CONSTRAINT fk_delivery_order
        FOREIGN KEY (order_id)
            references ${appSchema}.customer_order (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_delivery_order
        UNIQUE (order_id),

    CONSTRAINT chk_delivery_status
        CHECK (status in (
                          'CREATED',
                          'ASSIGNED',
                          'PICKED_UP',
                          'DELIVERED',
                          'CANCELED'
            ))
);

COMMENT ON TABLE ${appSchema}.delivery is 'Доставка заказа клиента';

COMMENT ON COLUMN ${appSchema}.delivery.id IS 'Уникальный идентификатор доставки';
COMMENT ON COLUMN ${appSchema}.delivery.order_id IS 'Идентификатор заказа, к которому относится доставка';
COMMENT ON COLUMN ${appSchema}.delivery.courier_id IS 'Идентификатор курьера, назначенного на доставку';
COMMENT ON COLUMN ${appSchema}.delivery.status IS 'Текущий статус доставки';
COMMENT ON COLUMN ${appSchema}.delivery.created_at IS 'Дата и время создания доставки';
COMMENT ON COLUMN ${appSchema}.delivery.updated_at IS 'Дата и время последнего обновления доставки';
COMMENT ON COLUMN ${appSchema}.delivery.picked_up_at IS 'Дата и время забора заказа курьером';
COMMENT ON COLUMN ${appSchema}.delivery.delivered_at IS 'Дата и время доставки заказа клиенту';

CREATE INDEX IF NOT EXISTS idx_delivery_order_id
    ON ${appSchema}.delivery (order_id);

CREATE INDEX IF NOT EXISTS idx_delivery_courier_id
    ON ${appSchema}.delivery (courier_id);

CREATE INDEX idx_delivery_status
    ON ${appSchema}.delivery (status);

CREATE INDEX idx_delivery_created_at
    ON ${appSchema}.delivery (created_at);