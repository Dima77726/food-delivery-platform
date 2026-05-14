ALTER TABLE ${appSchema}.customer_order_event
    DROP CONSTRAINT IF EXISTS fk_customer_order_event_order;

ALTER TABLE ${appSchema}.customer_order_event
    ADD CONSTRAINT fk_customer_order_event_order
        CHECK (event_type in (
                              'ORDER_CREATED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED',
                              'ORDER_COOKING_STARTED'
            ));