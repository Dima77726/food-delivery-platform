-- Transactional outbox для событий заказа.
--
-- Задача, которую он решает, называется двойной записью. Наивный вариант — отправить
-- сообщение в Kafka прямо из метода, меняющего статус заказа. Тогда возможны два исхода,
-- и оба плохие: транзакция откатилась, а сообщение уже улетело (потребители узнали
-- о событии, которого не было), либо транзакция зафиксировалась, а отправка упала
-- (событие произошло, но о нём никто не узнал). Двухфазного коммита между PostgreSQL
-- и Kafka нет и не будет.
--
-- Outbox убирает выбор: событие пишется в ту же таблицу, в ту же транзакцию, что и заказ.
-- Либо есть и заказ, и запись о событии, либо нет ни того, ни другого. Отправкой
-- занимается отдельный процесс, читающий эту таблицу.
--
-- Плата — доставка «хотя бы один раз»: между отправкой в Kafka и отметкой PUBLISHED
-- процесс может умереть, и сообщение уйдёт повторно. Поэтому у события есть event_id,
-- по которому потребитель обязан отсеивать дубликаты.

CREATE TABLE IF NOT EXISTS ${appSchema}.order_event_outbox
(
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMPTZ,

    CONSTRAINT uq_order_event_outbox_event_id
        UNIQUE (event_id),

    CONSTRAINT chk_order_event_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

COMMENT ON TABLE ${appSchema}.order_event_outbox IS 'Очередь событий на публикацию в Kafka; пишется в транзакции бизнес-операции';
COMMENT ON COLUMN ${appSchema}.order_event_outbox.event_id IS 'Идентификатор события; по нему потребитель отсеивает повторы';
COMMENT ON COLUMN ${appSchema}.order_event_outbox.aggregate_id IS 'Идентификатор заказа; он же ключ сообщения, чтобы события одного заказа шли по порядку';
COMMENT ON COLUMN ${appSchema}.order_event_outbox.payload IS 'Тело события в JSON';

-- Частичный индекс под единственный горячий запрос — «что осталось отправить».
-- Опубликованных записей со временем станет подавляющее большинство, и держать их
-- в индексе незачем.
CREATE INDEX IF NOT EXISTS idx_order_event_outbox_pending
    ON ${appSchema}.order_event_outbox (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_order_event_outbox_aggregate
    ON ${appSchema}.order_event_outbox (aggregate_type, aggregate_id);


-- Идемпотентность потребителя.
--
-- Доставка «хотя бы один раз» означает, что одно и то же событие может прийти дважды.
-- Без защиты клиент получил бы два одинаковых письма. Уникальный индекс по event_id
-- превращает повторную обработку в нарушение констрейнта, которое консьюмер спокойно
-- проглатывает: значит, это сообщение уже обработано.
ALTER TABLE ${appSchema}.notification
    ADD COLUMN IF NOT EXISTS event_id UUID;

COMMENT ON COLUMN ${appSchema}.notification.event_id IS 'Событие, породившее уведомление; защищает от дублей при повторной доставке';

-- Частичный: у уведомлений, созданных не из события, event_id остаётся NULL,
-- а обычный UNIQUE считал бы NULL-ы различными и индекс рос бы впустую.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_event_id
    ON ${appSchema}.notification (event_id)
    WHERE event_id IS NOT NULL;
