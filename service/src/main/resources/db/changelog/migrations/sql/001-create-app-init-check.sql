CREATE TABLE IF NOT EXISTS ${appSchema}.app_init_check (
                                                           id BIGSERIAL PRIMARY KEY,
                                                           code VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

INSERT INTO ${appSchema}.app_init_check (code, description)
VALUES ('INIT_OK', 'Initial Liquibase migration applied successfully')
    ON CONFLICT (code) DO NOTHING;