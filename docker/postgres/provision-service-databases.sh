#!/bin/sh
# Заводит базы и учётные записи сервисов: database-per-service.
#
# Каждый сервис получает свою базу и своего пользователя — владельца только этой базы.
# Ходить в чужую базу сервису нечем: у его учётной записи нет на это прав. Изоляция данных
# держится на правах доступа, а не на договорённости «в чужие таблицы не лазить».
#
# Почему отдельный одноразовый контейнер, а не /docker-entrypoint-initdb.d. Скрипты
# initdb выполняются ровно один раз — при создании тома. Существующий том новый сервис
# не увидел бы никогда, и каждый добавленный сервис требовал бы docker compose down -v
# с потерей данных монолита. Этот скрипт идемпотентен и выполняется при каждом up:
# недостающее создаёт, существующее не трогает, пароль приводит к значению из .env.
#
# Подключение — через стандартные переменные libpq: PGHOST, PGUSER, PGPASSWORD, PGDATABASE.
set -eu

# provision <база> <пользователь> <пароль>
provision() {
    db="$1"
    user="$2"
    password="$3"

    # \gexec выполняет результат запроса как команду: так условное создание укладывается
    # в один SQL без PL/pgSQL. CREATE DATABASE нельзя выполнить внутри DO-блока —
    # он не работает в транзакции, — поэтому DO здесь и не подошёл бы.
    #
    # Имена и пароль подставляются через format с %I и %L, а не склейкой строк: значения
    # приходят из .env, и экранирование обязано делать сама база.
    psql -v ON_ERROR_STOP=1 -q -v db="$db" -v user="$user" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'user', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'user')
\gexec

SELECT format('ALTER ROLE %I PASSWORD %L', :'user', :'password')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db')
\gexec

-- По умолчанию подключаться к любой базе может любая роль (право CONNECT у PUBLIC).
-- Отзываем его: подключиться к базе сервиса может только её владелец.
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'db')
\gexec
SQL

    echo "База $db и учётная запись $user готовы"
}

# База монолита тоже закрывается от посторонних: иначе пользователь сервиса уведомлений
# мог бы подключиться к ней и читать чужие таблицы — ровно то, чего изоляция и не допускает.
# Сам монолит ходит под суперпользователем из POSTGRES_USER, на него отзыв не влияет.
psql -v ON_ERROR_STOP=1 -q -v db="$PGDATABASE" <<'SQL'
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'db')
\gexec
SQL

provision "$NOTIFICATION_DB_NAME" "$NOTIFICATION_DB_USER" "$NOTIFICATION_DB_PASSWORD"

provision "$CATALOG_DB_NAME" "$CATALOG_DB_USER" "$CATALOG_DB_PASSWORD"

provision "$IDENTITY_DB_NAME" "$IDENTITY_DB_USER" "$IDENTITY_DB_PASSWORD"

provision "$ORDER_DB_NAME" "$ORDER_DB_USER" "$ORDER_DB_PASSWORD"

provision "$PAYMENT_DB_NAME" "$PAYMENT_DB_USER" "$PAYMENT_DB_PASSWORD"

# ---------- Учётная запись только для чтения: Grafana и ручной анализ ----------
#
# Аналитику и дашбордам нужны данные всех сервисов, но не право их менять: поэтому отдельная роль
# с SELECT, а не учётные записи сервисов и не суперпользователь. Секреты (хеши паролей, refresh-токены,
# привязки внешних учётных записей) ей не видны. Без ANALYTICS_DB_PASSWORD роль не заводится.
if [ -n "${ANALYTICS_DB_PASSWORD:-}" ]; then
    reader="${ANALYTICS_DB_USER:-analytics_reader}"

    psql -v ON_ERROR_STOP=1 -q -v user="$reader" -v password="$ANALYTICS_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'user', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'user')
\gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'user', :'password')
\gexec
SQL

    # grant_read <база> <владелец таблиц>
    grant_read() {
        db="$1"
        owner="$2"
        psql -v ON_ERROR_STOP=1 -q -v db="$db" -v user="$reader" <<'SQL'
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'user')
\gexec
SQL
        psql -v ON_ERROR_STOP=1 -q -d "$db" -v user="$reader" -v owner="$owner" <<'SQL'
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'user')
\gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', :'user')
\gexec
-- Таблицы, которые миграции сервиса создадут позже, тоже станут читаемыми.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON TABLES TO %I', :'owner', :'user')
\gexec
SQL
    }

    grant_read "$NOTIFICATION_DB_NAME" "$NOTIFICATION_DB_USER"
    grant_read "$CATALOG_DB_NAME" "$CATALOG_DB_USER"
    grant_read "$ORDER_DB_NAME" "$ORDER_DB_USER"
    grant_read "$PAYMENT_DB_NAME" "$PAYMENT_DB_USER"
    grant_read "$IDENTITY_DB_NAME" "$IDENTITY_DB_USER"

    # В базе пользователей - только безопасные столбцы.
    psql -v ON_ERROR_STOP=1 -q -d "$IDENTITY_DB_NAME" -v user="$reader" <<'SQL'
SELECT format('REVOKE SELECT ON TABLE %I FROM %I', t, :'user')
FROM unnest(ARRAY['app_user', 'refresh_token', 'external_identity']) AS t
WHERE to_regclass('public.' || t) IS NOT NULL
\gexec
SELECT format('GRANT SELECT (id, email, full_name, phone, is_enabled, created_at, updated_at) ON TABLE app_user TO %I', :'user')
WHERE to_regclass('public.app_user') IS NOT NULL
\gexec
SQL

    echo "Учётная запись только для чтения $reader готова"
fi
