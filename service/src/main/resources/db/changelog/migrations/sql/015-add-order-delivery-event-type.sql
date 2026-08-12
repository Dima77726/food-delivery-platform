ALTER TABLE ${appSchema}.customer_order_event
    DROP constraint if exists chk_customer_order_event_type;

ALTER TABLE ${appSchema}.customer_order_event
    ADD CONSTRAINT chk_customer_order_event_type
        CHECK (event_type in (
                              'ORDER_CREATED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED',
                              'ORDER_COOKING_STARTED',
                              'ORDER_READY_FOR_DELIVERY',
                              'ORDER_PICKED_UP_BY_COURIER',
                              'ORDER_DELIVERED'
            ));