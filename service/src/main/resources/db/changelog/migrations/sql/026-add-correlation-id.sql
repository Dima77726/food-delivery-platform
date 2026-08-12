-- Сквозная метка запроса в трёх таблицах.
--
-- Одно обращение к API оставляет следы в разных местах и в разное время: запись в журнале
-- аудита появляется в транзакции запроса, событие в outbox — там же, а уведомление рождается
-- секундой позже, в другом потоке, после того как событие проехало через Kafka. Связать эти
-- строки между собой сейчас нечем: по времени они расходятся, а общего идентификатора нет.
--
-- correlation_id и есть этот общий идентификатор. Он приходит из заголовка X-Correlation-Id
-- либо выдаётся самим приложением, и дальше едет по всей цепочке.
--
-- Колонка везде необязательная, и это не небрежность. Записи появляются не только из
-- HTTP-запросов: заказ может смениться фоновой задачей, у которой никакого запроса нет.
-- NOT NULL заставил бы придумывать таким строкам фиктивную метку, и она была бы враньём.

ALTER TABLE ${appSchema}.audit_log
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

COMMENT ON COLUMN ${appSchema}.audit_log.correlation_id IS 'Метка запроса, породившего запись; NULL для действий вне HTTP-запроса';

-- Индекс только здесь. Журнал аудита — единственная из трёх таблиц, где по метке
-- действительно ищут: «покажи всё, что произошло в рамках вот этого обращения».
-- В outbox и уведомлениях она едет как справочное значение, и запросов по ней нет.
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation_id
    ON ${appSchema}.audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;


ALTER TABLE ${appSchema}.order_event_outbox
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

COMMENT ON COLUMN ${appSchema}.order_event_outbox.correlation_id IS 'Метка запроса, в котором произошло событие; уезжает заголовком в Kafka';


ALTER TABLE ${appSchema}.notification
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

COMMENT ON COLUMN ${appSchema}.notification.correlation_id IS 'Метка исходного запроса, дошедшая через Kafka; связывает уведомление с действием пользователя';
