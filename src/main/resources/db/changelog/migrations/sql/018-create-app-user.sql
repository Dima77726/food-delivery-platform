CREATE TABLE IF NOT EXISTS ${appSchema}.app_user
(
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    phone         VARCHAR(50),
    is_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_app_user_email_not_blank
        CHECK (LENGTH(TRIM(email)) > 0)
);

-- Регистр e-mail не должен создавать двух разных пользователей: Ivan@mail.ru и ivan@mail.ru —
-- один человек. Уникальность по LOWER(email), а не по email.
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_email_lower
    ON ${appSchema}.app_user (LOWER(email));

COMMENT ON TABLE ${appSchema}.app_user IS 'Пользователь платформы: клиент, владелец ресторана, курьер или администратор';
COMMENT ON COLUMN ${appSchema}.app_user.email IS 'E-mail, он же логин';
COMMENT ON COLUMN ${appSchema}.app_user.password_hash IS 'BCrypt-хеш пароля; сам пароль не хранится нигде';
COMMENT ON COLUMN ${appSchema}.app_user.is_enabled IS 'Отключённый пользователь не может войти, но его заказы сохраняются';


CREATE TABLE IF NOT EXISTS ${appSchema}.app_user_role
(
    user_id BIGINT      NOT NULL,
    role    VARCHAR(50) NOT NULL,

    CONSTRAINT pk_app_user_role
        PRIMARY KEY (user_id, role),

    CONSTRAINT fk_app_user_role_user
        FOREIGN KEY (user_id)
            REFERENCES ${appSchema}.app_user (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_app_user_role
        CHECK (role IN ('CUSTOMER', 'RESTAURANT_OWNER', 'COURIER', 'ADMIN'))
);

COMMENT ON TABLE ${appSchema}.app_user_role IS 'Роли пользователя; ролей может быть несколько';

CREATE INDEX IF NOT EXISTS idx_app_user_role_user_id
    ON ${appSchema}.app_user_role (user_id);


-- Связь владельца с рестораном. Отдельная таблица, а не колонка owner_id в restaurant:
-- у сети заведений один владелец на несколько точек, и наоборот — у точки может быть
-- несколько управляющих.
CREATE TABLE IF NOT EXISTS ${appSchema}.restaurant_manager
(
    user_id       BIGINT      NOT NULL,
    restaurant_id BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_restaurant_manager
        PRIMARY KEY (user_id, restaurant_id),

    CONSTRAINT fk_restaurant_manager_user
        FOREIGN KEY (user_id)
            REFERENCES ${appSchema}.app_user (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_restaurant_manager_restaurant
        FOREIGN KEY (restaurant_id)
            REFERENCES ${appSchema}.restaurant (id)
            ON DELETE CASCADE
);

COMMENT ON TABLE ${appSchema}.restaurant_manager IS 'Кто из пользователей управляет каким рестораном';

CREATE INDEX IF NOT EXISTS idx_restaurant_manager_restaurant_id
    ON ${appSchema}.restaurant_manager (restaurant_id);
