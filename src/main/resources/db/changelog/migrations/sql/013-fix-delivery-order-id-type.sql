ALTER TABLE ${appSchema}.delivery
    ALTER COLUMN order_id DROP DEFAULT;

DROP SEQUENCE if exists ${appSchema}.delivery_order_id_seq;

ALTER TABLE ${appSchema}.delivery
    ALTER COLUMN order_id TYPE BIGINT;