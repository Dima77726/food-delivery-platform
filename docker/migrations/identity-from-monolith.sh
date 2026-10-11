#!/bin/sh
# Одноразовый перенос пользователей из базы монолита в базу identity-service.
#
# Пользователи переехали в отдельный сервис, а данные остались в таблицах app_user,
# app_user_role и restaurant_manager схемы монолита. На идентификаторы пользователей ссылаются
# заказы, корзины, доставки, отзывы и уведомления во всех сервисах, поэтому перенос обязан
# сохранить их один в один — новые номера отдали бы чужие заказы не тем людям.
#
# Хеши паролей переносятся как есть: это BCrypt, и пользователи входят со своими паролями.
#
# Устроен так же, как перенос каталога (catalog-from-monolith.sh):
#   - ждёт, пока обе схемы появятся;
#   - переносит, только если в сервисе ещё нет пользователей;
#   - всё в одной транзакции и со сдвигом последовательности.
#
# Повторный запуск безопасен:
#   docker compose up identity-data-migration
set -eu

MONOLITH_DB="$MONOLITH_DB_NAME"
MONOLITH_SCHEMA="${MONOLITH_SCHEMA:-food_app}"
TARGET_DB="$IDENTITY_DB_NAME"
WAIT_SECONDS="${WAIT_SECONDS:-600}"

table_exists() {
    # $1 — база, $2 — схема, $3 — таблица
    psql -d "$1" -tAqc "SELECT to_regclass('$2.$3') IS NOT NULL" | grep -q t
}

echo "Жду схемы монолита ($MONOLITH_DB.$MONOLITH_SCHEMA) и сервиса пользователей ($TARGET_DB.public)..."

waited=0
until table_exists "$MONOLITH_DB" "$MONOLITH_SCHEMA" restaurant_manager && table_exists "$TARGET_DB" public restaurant_manager; do
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
        echo "Схемы не появились за ${WAIT_SECONDS} с. Запустите монолит и identity-service,"
        echo "затем повторите: docker compose up identity-data-migration"
        exit 0
    fi
    sleep 2
    waited=$((waited + 2))
done

target_rows=$(psql -d "$TARGET_DB" -tAqc "SELECT COUNT(*) FROM app_user")

if [ "$target_rows" -ne 0 ]; then
    echo "Пользователи уже есть ($target_rows) — перенос выполнен, ничего не делаю"
    exit 0
fi

DUMP_DIR=$(mktemp -d)

psql -d "$MONOLITH_DB" -v ON_ERROR_STOP=1 -q <<SQL
\copy (SELECT id, email, password_hash, full_name, phone, is_enabled, created_at, updated_at FROM ${MONOLITH_SCHEMA}.app_user) TO '${DUMP_DIR}/app_user.csv' CSV
\copy (SELECT user_id, role FROM ${MONOLITH_SCHEMA}.app_user_role) TO '${DUMP_DIR}/app_user_role.csv' CSV
\copy (SELECT user_id, restaurant_id, created_at FROM ${MONOLITH_SCHEMA}.restaurant_manager) TO '${DUMP_DIR}/restaurant_manager.csv' CSV
SQL

# Пустота проверяется ещё раз под блокировкой: между проверкой выше и этой точкой кто-то мог
# успеть зарегистрироваться, и перенос столкнулся бы с его идентификатором.
psql -d "$TARGET_DB" -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;

LOCK TABLE app_user, app_user_role, restaurant_manager IN EXCLUSIVE MODE;

DO \$\$
BEGIN
    IF EXISTS (SELECT 1 FROM app_user) THEN
        RAISE EXCEPTION 'Пользователи появились во время переноса — перенос отменён';
    END IF;
END
\$\$;

\copy app_user (id, email, password_hash, full_name, phone, is_enabled, created_at, updated_at) FROM '${DUMP_DIR}/app_user.csv' CSV
\copy app_user_role (user_id, role) FROM '${DUMP_DIR}/app_user_role.csv' CSV
\copy restaurant_manager (user_id, restaurant_id, created_at) FROM '${DUMP_DIR}/restaurant_manager.csv' CSV

-- Без сдвига последовательность выдала бы 1, 2, 3... — номера, уже занятые перенесёнными строками.
\o /dev/null
SELECT setval(pg_get_serial_sequence('app_user', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM app_user;
\o

COMMIT;
SQL

rm -rf "$DUMP_DIR"

psql -d "$TARGET_DB" -tAqc "SELECT 'Перенесено: пользователей ' || (SELECT COUNT(*) FROM app_user) || ', ролей ' || (SELECT COUNT(*) FROM app_user_role) || ', прав на рестораны ' || (SELECT COUNT(*) FROM restaurant_manager)"
