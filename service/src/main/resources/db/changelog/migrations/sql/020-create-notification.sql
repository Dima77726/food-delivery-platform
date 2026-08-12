CREATE TABLE IF NOT EXISTS ${appSchema}.notification
(
    id            BIGSERIAL PRIMARY KEY,
    recipient_id  BIGINT       NOT NULL,
    channel       VARCHAR(50)  NOT NULL,
    type          VARCHAR(100) NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    body          VARCHAR(2000) NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    order_id      BIGINT,
    failure_reason VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at       TIMESTAMPTZ,

    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_id)
            REFERENCES ${appSchema}.app_user (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_notification_order
        FOREIGN KEY (order_id)
            REFERENCES ${appSchema}.customer_order (id)
            ON DELETE SET NULL,

    CONSTRAINT chk_notification_channel
        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),

    CONSTRAINT chk_notification_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

COMMENT ON TABLE ${appSchema}.notification IS 'Очередь и журнал уведомлений пользователям';
COMMENT ON COLUMN ${appSchema}.notification.status IS 'PENDING — создано, но не отправлено; отправкой займётся отдельный процесс';

CREATE INDEX IF NOT EXISTS idx_notification_recipient_id
    ON ${appSchema}.notification (recipient_id);

CREATE INDEX IF NOT EXISTS idx_notification_order_id
    ON ${appSchema}.notification (order_id);

-- Индекс под выборку «что осталось отправить»: частичный, потому что отправленные
-- уведомления этот запрос не интересуют, а их со временем станет большинство.
CREATE INDEX IF NOT EXISTS idx_notification_pending
    ON ${appSchema}.notification (created_at)
    WHERE status = 'PENDING';
