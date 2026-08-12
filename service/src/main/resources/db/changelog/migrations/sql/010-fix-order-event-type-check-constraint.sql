ALTER TABLE ${appSchema}.customer_order_event
    DROP CONSTRAINT IF EXISTS chk_customer_order_event_type;

ALTER TABLE ${appSchema}.customer_order_event
    ADD CONSTRAINT chk_customer_order_event_type
        CHECK (event_type IN (
                              'ORDER_CREATED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED',
                              'ORDER_COOKING_STARTED'
            ));

ALTER TABLE ${appSchema}.customer_order_event
    DROP CONSTRAINT IF EXISTS fk_customer_order_event_order;

ALTER TABLE ${appSchema}.customer_order_event
    ADD CONSTRAINT fk_customer_order_event_order
        FOREIGN KEY (order_id)
            REFERENCES ${appSchema}.customer_order (id)
            ON DELETE CASCADE;