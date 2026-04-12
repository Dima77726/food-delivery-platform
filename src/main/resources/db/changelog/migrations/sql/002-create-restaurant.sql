CREATE TABLE IF NOT EXISTS ${appSchema}.restaurant (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    city VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ${appSchema}.restaurant IS 'Справочник ресторанов платформы';

COMMENT ON COLUMN ${appSchema}.restaurant.id IS 'Уникальный идентификатор ресторана';
COMMENT ON COLUMN ${appSchema}.restaurant.name IS 'Название ресторана';
COMMENT ON COLUMN ${appSchema}.restaurant.city IS 'Город, в котором работает ресторан';
COMMENT ON COLUMN ${appSchema}.restaurant.is_active IS 'Признак доступности ресторана для заказов';
COMMENT ON COLUMN ${appSchema}.restaurant.created_at IS 'Дата и время создания записи';

INSERT INTO ${appSchema}.restaurant (name, city, is_active)
VALUES
    ('Pizza House', 'Moscow', TRUE),
    ('Burger Point', 'Saint Petersburg', TRUE),
    ('Sushi Time', 'Kazan', FALSE)
ON CONFLICT DO NOTHING;