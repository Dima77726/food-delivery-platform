ALTER TABLE ${appSchema}.customer_order
    drop constraint if exists chk_customer_order_status;

ALTER TABLE ${appSchema}.customer_order
    ADD constraint chk_customer_order_status
        check (status in (
                          'CREATED',
                          'PAID',
                          'ACCEPTED',
                          'COOKING',
                          'READY_FOR_DELIVERY',
                          'IN_DELIVERY',
                          'DELIVERED',
                          'CANCELED'
            ));