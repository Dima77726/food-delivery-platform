ALTER TABLE ${appSchema}.customer_order_item
    ALTER COLUMN price TYPE NUMERIC(10, 2);

ALTER TABLE ${appSchema}.customer_order_item
    DROP CONSTRAINT IF EXISTS chk_customer_order_item_line_total_non_negative;

ALTER TABLE ${appSchema}.customer_order_item
    ADD CONSTRAINT chk_customer_order_item_line_total_non_negative
        CHECK (line_total >= 0);
