#!/bin/sh
# Одноразовый перенос каталога из базы монолита в базу catalog-service.
#
# Рестораны и меню переехали в отдельный сервис, а данные остались там, где были: в таблицах
# restaurant, menu_category и menu_item схемы монолита. На них по идентификаторам ссылаются
# корзины и заказы, поэтому перенос обязан сохранить идентификаторы один в один — новые
# номера разорвали бы связь заказа с блюдом.
#
# Когда и как выполняется:
#   - ждёт, пока обе схемы появятся: монолитную создаёт Liquibase монолита, каталожную —
#     Liquibase каталога на его старте;
#   - переносит, только если каталог пуст. Непустой каталог означает, что перенос уже был
#     или что каталог живёт своей жизнью, и тогда трогать его нельзя;
#   - всё в одной транзакции: либо перенесены все три таблицы, либо ни одной;
#   - последовательности сдвигаются за максимальный идентификатор, иначе первый же новый
#     ресторан получил бы занятый номер.
#
# Повторный запуск безопасен: каталог уже не пуст, и скрипт ничего не делает.
#   docker compose up catalog-data-migration
#
# Подключение — суперпользователем PostgreSQL через переменные libpq (PGHOST, PGUSER,
# PGPASSWORD): ему видны обе базы. Сервисам такого доступа не дано и не нужно.
set -eu

MONOLITH_DB="$MONOLITH_DB_NAME"
MONOLITH_SCHEMA="${MONOLITH_SCHEMA:-food_app}"
CATALOG_DB="$CATALOG_DB_NAME"
WAIT_SECONDS="${WAIT_SECONDS:-600}"

table_exists() {
    # $1 — база, $2 — схема, $3 — таблица
    psql -d "$1" -tAqc "SELECT to_regclass('$2.$3') IS NOT NULL" | grep -q t
}

echo "Жду схемы монолита ($MONOLITH_DB.$MONOLITH_SCHEMA) и каталога ($CATALOG_DB.public)..."

waited=0
until table_exists "$MONOLITH_DB" "$MONOLITH_SCHEMA" menu_item && table_exists "$CATALOG_DB" public menu_item; do
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
        # Не ошибка: при запуске сервисов из IDE они могут ещё не стартовать. Скрипт
        # запускается повторно той же командой, когда оба сервиса поднимутся.
        echo "Схемы не появились за ${WAIT_SECONDS} с. Запустите монолит и каталог,"
        echo "затем повторите: docker compose up catalog-data-migration"
        exit 0
    fi
    sleep 2
    waited=$((waited + 2))
done

catalog_rows=$(psql -d "$CATALOG_DB" -tAqc "SELECT (SELECT COUNT(*) FROM restaurant) + (SELECT COUNT(*) FROM menu_category) + (SELECT COUNT(*) FROM menu_item)")

if [ "$catalog_rows" -ne 0 ]; then
    echo "Каталог не пуст ($catalog_rows строк) — перенос уже выполнен, ничего не делаю"
    exit 0
fi

DUMP_DIR=$(mktemp -d)

# Выгрузка из монолита. Список колонок явный в обе стороны: порядок колонок в таблицах
# разный (монолитные таблицы росли миграциями), а COPY сопоставляет по позиции.
psql -d "$MONOLITH_DB" -v ON_ERROR_STOP=1 -q <<SQL
\copy (SELECT id, name, description, city, is_active, created_at, updated_at FROM ${MONOLITH_SCHEMA}.restaurant) TO '${DUMP_DIR}/restaurant.csv' CSV
\copy (SELECT id, restaurant_id, name, sort_order, is_archived, created_at, updated_at FROM ${MONOLITH_SCHEMA}.menu_category) TO '${DUMP_DIR}/menu_category.csv' CSV
\copy (SELECT id, category_id, name, description, price, is_available, is_archived, sort_order, created_at, updated_at FROM ${MONOLITH_SCHEMA}.menu_item) TO '${DUMP_DIR}/menu_item.csv' CSV
SQL

# Загрузка в каталог одной транзакцией. Пустота проверяется ещё раз под блокировкой:
# между проверкой выше и этой точкой каталог мог успеть что-то записать, и тогда перенос
# столкнулся бы с его идентификаторами.
psql -d "$CATALOG_DB" -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;

LOCK TABLE restaurant, menu_category, menu_item IN EXCLUSIVE MODE;

DO \$\$
BEGIN
    IF EXISTS (SELECT 1 FROM restaurant) OR EXISTS (SELECT 1 FROM menu_category) OR EXISTS (SELECT 1 FROM menu_item) THEN
        RAISE EXCEPTION 'Каталог перестал быть пустым во время переноса — перенос отменён';
    END IF;
END
\$\$;

\copy restaurant (id, name, description, city, is_active, created_at, updated_at) FROM '${DUMP_DIR}/restaurant.csv' CSV
\copy menu_category (id, restaurant_id, name, sort_order, is_archived, created_at, updated_at) FROM '${DUMP_DIR}/menu_category.csv' CSV
\copy menu_item (id, category_id, name, description, price, is_available, is_archived, sort_order, created_at, updated_at) FROM '${DUMP_DIR}/menu_item.csv' CSV

-- Без сдвига последовательность выдала бы 1, 2, 3... — номера, уже занятые перенесёнными строками.
-- Вывод setval в лог не нужен — он подавляется до конца транзакции.
\o /dev/null
SELECT setval(pg_get_serial_sequence('restaurant', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM restaurant;
SELECT setval(pg_get_serial_sequence('menu_category', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM menu_category;
SELECT setval(pg_get_serial_sequence('menu_item', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM menu_item;
\o

COMMIT;
SQL

rm -rf "$DUMP_DIR"

psql -d "$CATALOG_DB" -tAqc "SELECT 'Перенесено: ресторанов ' || (SELECT COUNT(*) FROM restaurant) || ', категорий ' || (SELECT COUNT(*) FROM menu_category) || ', блюд ' || (SELECT COUNT(*) FROM menu_item)"
