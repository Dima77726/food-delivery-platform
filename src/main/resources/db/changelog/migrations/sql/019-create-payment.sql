CREATE TABLE IF NOT EXISTS ${appSchema}.payment
(
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    customer_id    BIGINT         NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    status         VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    method         VARCHAR(50)    NOT NULL,
    -- Ключ идемпотентности: клиент присылает его сам. Повторный запрос с тем же ключом
    -- не создаёт второй платёж, а возвращает результат первого. Без этого двойной клик
    -- по кнопке «оплатить» списывал бы деньги дважды.
    idempotency_key VARCHAR(100)  NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_order
        FOREIGN KEY (order_id)
            REFERENCES ${appSchema}.customer_order (id)
            ON DELETE RESTRICT,

    CONSTRAINT uq_payment_idempotency_key
        UNIQUE (idempotency_key),

    CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')),

    CONSTRAINT chk_payment_method
        CHECK (method IN ('CARD', 'CASH_ON_DELIVERY')),

    CONSTRAINT chk_payment_amount_positive
        CHECK (amount > 0)
);

-- Успешный платёж у заказа может быть только один. Частичный индекс, а не UNIQUE(order_id):
-- неудачных попыток может быть сколько угодно, и они должны сохраняться для разбора.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_succeeded_order
    ON ${appSchema}.payment (order_id)
    WHERE status = 'SUCCEEDED';

COMMENT ON TABLE ${appSchema}.payment IS 'Платежи по заказам, включая неудачные попытки';
COMMENT ON COLUMN ${appSchema}.payment.idempotency_key IS 'Ключ идемпотентности от клиента; защищает от повторной оплаты';
COMMENT ON COLUMN ${appSchema}.payment.failure_reason IS 'Причина отказа для статуса FAILED';

CREATE INDEX IF NOT EXISTS idx_payment_order_id
    ON ${appSchema}.payment (order_id);

CREATE INDEX IF NOT EXISTS idx_payment_customer_id
    ON ${appSchema}.payment (customer_id);

CREATE INDEX IF NOT EXISTS idx_payment_status
    ON ${appSchema}.payment (status);
