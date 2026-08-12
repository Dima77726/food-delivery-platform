-- Появление модуля Payment добавило три типа событий в историю заказа.
-- Enum OrderEventType и этот check-констрейнт обязаны меняться вместе: база не должна
-- принимать значение, которое приложение не сможет прочитать обратно.
ALTER TABLE ${appSchema}.customer_order_event
    DROP CONSTRAINT IF EXISTS chk_customer_order_event_type;

ALTER TABLE ${appSchema}.customer_order_event
    ADD CONSTRAINT chk_customer_order_event_type
        CHECK (event_type IN (
                              'ORDER_CREATED',
                              'ORDER_PAID',
                              'ORDER_PAYMENT_FAILED',
                              'ORDER_REFUNDED',
                              'ORDER_CANCELED',
                              'ORDER_ACCEPTED',
                              'ORDER_COOKING_STARTED',
                              'ORDER_READY_FOR_DELIVERY',
                              'ORDER_PICKED_UP_BY_COURIER',
                              'ORDER_DELIVERED'
            ));
