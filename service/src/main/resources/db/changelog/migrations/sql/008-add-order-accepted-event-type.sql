ALTER TABLE ${appSchema}.customer_order_event
DROP constraint if exists chk_customer_order_event_type;

alter table ${appSchema}.customer_order_event
    add constraint chk_customer_order_event_type
        check (event_type in (
                              'ORDER_CREATED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED'
            ));