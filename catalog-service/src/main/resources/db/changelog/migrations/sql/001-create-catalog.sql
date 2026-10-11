-- Схема каталога в собственной базе сервиса.
--
-- Итог миграций 002, 003, 004 и 023 монолита, собранный в одну: базе нужно конечное
-- состояние, а не путь к нему. Типы колонок совпадают с монолитными один в один — иначе
-- перенос данных из монолита (docker/migrations/catalog-from-monolith.sh) упирался бы
-- в несовпадение длин и точности.
--
-- Начальных данных здесь нет намеренно. Рестораны и меню уже существуют — в базе монолита,
-- и на них ссылаются корзины и заказы по идентификаторам. Засей каталог свои строки сам,
-- и идентификаторы разошлись бы с теми, что уже лежат в заказах. Поэтому единственный
-- источник начальных данных — одноразовый перенос из монолита с сохранением идентификаторов.

CREATE TABLE IF NOT EXISTS restaurant
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    city        VARCHAR(255)  NOT NULL,
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE restaurant IS 'Справочник ресторанов платформы';
COMMENT ON COLUMN restaurant.is_active IS 'Признак доступности ресторана для заказов';

CREATE TABLE IF NOT EXISTS menu_category
(
    id            BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT       NOT NULL,
    name          VARCHAR(255) NOT NULL,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    is_archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Внутри каталога внешние ключи остаются: обе таблицы живут в одной базе,
    -- и целостность здесь по-прежнему держит сама база.
    CONSTRAINT fk_menu_category_restaurant
        FOREIGN KEY (restaurant_id)
            REFERENCES restaurant (id)
            ON DELETE CASCADE
);

COMMENT ON TABLE menu_category IS 'Категория меню ресторана';
COMMENT ON COLUMN menu_category.is_archived IS 'Категория убрана из меню; существующие заказы на неё не влияет';

-- Уникальность имени — только среди неархивных: «Маргариту» можно завести заново
-- после архивирования прежней.
CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_category_active_name
    ON menu_category (restaurant_id, name)
    WHERE is_archived = FALSE;

CREATE INDEX IF NOT EXISTS idx_menu_category_active
    ON menu_category (restaurant_id)
    WHERE is_archived = FALSE;

CREATE TABLE IF NOT EXISTS menu_item
(
    id           BIGSERIAL PRIMARY KEY,
    category_id  BIGINT         NOT NULL,
    name         VARCHAR(255)   NOT NULL,
    description  VARCHAR(100),
    price        NUMERIC(10, 2) NOT NULL,
    is_available BOOLEAN        NOT NULL DEFAULT TRUE,
    is_archived  BOOLEAN        NOT NULL DEFAULT FALSE,
    sort_order   INTEGER        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_menu_item_category
        FOREIGN KEY (category_id)
            REFERENCES menu_category (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_menu_item_price_positive
        CHECK (price >= 0)
);

COMMENT ON TABLE menu_item IS 'Позиции меню ресторана';
COMMENT ON COLUMN menu_item.is_available IS 'Временно нет в наличии; обратимо';
COMMENT ON COLUMN menu_item.is_archived IS 'Блюдо убрано из продажи навсегда; в отличие от is_available это необратимо';

CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_item_active_name
    ON menu_item (category_id, name)
    WHERE is_archived = FALSE;

CREATE INDEX IF NOT EXISTS idx_menu_item_active
    ON menu_item (category_id)
    WHERE is_archived = FALSE;
