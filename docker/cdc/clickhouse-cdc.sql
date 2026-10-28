-- Потребитель CDC в ClickHouse: топик Debezium cdc.public.customer_order → история статусов заказов.
--
-- Три объекта — стандартная схема ClickHouse для Kafka:
--   1. таблица на движке Kafka — не хранит данные, это курсор группы потребителя clickhouse-cdc;
--   2. MergeTree — где история лежит на самом деле;
--   3. материализованное представление — «триггер»: каждый прочитанный блок из (1) вставляется в (2).
-- Сообщение — плоский JSON после ExtractNewRecordState: поля строки плюс __op (c/u/d/r) и __source_ts_ms.

CREATE TABLE IF NOT EXISTS cdc_customer_order_queue
(
    id             Int64,
    restaurant_id  Int64,
    customer_id    Int64,
    status         String,
    total_amount   String,
    __op           String,
    __source_ts_ms Int64,
    __deleted      String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'cdc.public.customer_order',
         kafka_group_name = 'clickhouse-cdc',
         kafka_format = 'JSONEachRow',
         kafka_skip_broken_messages = 10,
         input_format_skip_unknown_fields = 1;

CREATE TABLE IF NOT EXISTS order_status_history
(
    order_id      Int64,
    restaurant_id Int64,
    customer_id   Int64,
    status        LowCardinality(String),
    total_amount  Decimal(14, 2),
    operation     LowCardinality(String),
    changed_at    DateTime64(3)
)
ENGINE = MergeTree
ORDER BY (order_id, changed_at);

CREATE MATERIALIZED VIEW IF NOT EXISTS cdc_customer_order_mv TO order_status_history AS
SELECT id                                          AS order_id,
       restaurant_id,
       customer_id,
       status,
       toDecimal64OrZero(total_amount, 2)          AS total_amount,
       __op                                        AS operation,
       fromUnixTimestamp64Milli(__source_ts_ms)    AS changed_at
FROM cdc_customer_order_queue;
