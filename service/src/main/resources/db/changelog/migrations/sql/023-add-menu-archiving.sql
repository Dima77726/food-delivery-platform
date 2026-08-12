-- Управление меню требует уметь убирать позиции из продажи. Удалять их нельзя:
-- customer_order_item ссылается на menu_item с ON DELETE RESTRICT, и это правильно —
-- заказ обязан помнить, что именно купил клиент. Поэтому архивирование, а не удаление.

ALTER TABLE ${appSchema}.restaurant
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE ${appSchema}.menu_category
    ADD COLUMN IF NOT EXISTS is_archived BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ${appSchema}.menu_category
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE ${appSchema}.menu_item
    ADD COLUMN IF NOT EXISTS is_archived BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ${appSchema}.menu_item
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

COMMENT ON COLUMN ${appSchema}.menu_category.is_archived IS 'Категория убрана из меню; существующие заказы на неё не влияет';
COMMENT ON COLUMN ${appSchema}.menu_item.is_archived IS 'Блюдо убрано из продажи навсегда; в отличие от is_available это необратимо';

-- Уникальность имени перевешивается на неархивные строки.
-- Со старым constraint'ом нельзя было бы завести «Маргариту» заново после архивирования
-- прежней: строка никуда не делась и продолжала занимать имя.
ALTER TABLE ${appSchema}.menu_category
    DROP CONSTRAINT IF EXISTS uq_menu_category_restaurant_name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_category_active_name
    ON ${appSchema}.menu_category (restaurant_id, name)
    WHERE is_archived = FALSE;

ALTER TABLE ${appSchema}.menu_item
    DROP CONSTRAINT IF EXISTS uq_menu_item_category_name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_item_active_name
    ON ${appSchema}.menu_item (category_id, name)
    WHERE is_archived = FALSE;

-- Меню читается на каждый заход в ресторан и всегда только по неархивным строкам.
CREATE INDEX IF NOT EXISTS idx_menu_category_active
    ON ${appSchema}.menu_category (restaurant_id)
    WHERE is_archived = FALSE;

CREATE INDEX IF NOT EXISTS idx_menu_item_active
    ON ${appSchema}.menu_item (category_id)
    WHERE is_archived = FALSE;
