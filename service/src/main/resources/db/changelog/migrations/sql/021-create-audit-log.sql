CREATE TABLE IF NOT EXISTS ${appSchema}.audit_log
(
    id            BIGSERIAL PRIMARY KEY,
    actor_id      BIGINT,
    actor_email   VARCHAR(255),
    action        VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id   VARCHAR(100),
    outcome       VARCHAR(50)  NOT NULL,
    details       VARCHAR(2000),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_audit_log_outcome
        CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

-- Внешнего ключа на app_user намеренно нет: журнал обязан пережить удаление пользователя.
-- Поэтому же рядом с actor_id хранится actor_email — на момент действия, а не текущий.
COMMENT ON TABLE ${appSchema}.audit_log IS 'Журнал значимых действий; только на запись и чтение';
COMMENT ON COLUMN ${appSchema}.audit_log.actor_id IS 'Кто выполнил действие; NULL для анонимных попыток';
COMMENT ON COLUMN ${appSchema}.audit_log.actor_email IS 'E-mail на момент действия; не обновляется вслед за пользователем';
COMMENT ON COLUMN ${appSchema}.audit_log.resource_id IS 'Строка, а не BIGINT: журнал общий для ресурсов с разными типами ключей';

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id
    ON ${appSchema}.audit_log (actor_id);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at
    ON ${appSchema}.audit_log (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_resource
    ON ${appSchema}.audit_log (resource_type, resource_id);
