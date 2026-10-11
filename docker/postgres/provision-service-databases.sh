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
