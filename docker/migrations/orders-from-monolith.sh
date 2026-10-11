#!/bin/sh
# Одноразовый перенос заказов и платежей из базы монолита в базы order-service и payment-service.
#
# Корзины, заказы, их история, доставки, журнал аудита заказов и неотправленные события outbox
# уезжают в базу заказов; платежи — в базу платежей. Идентификаторы сохраняются один в один:
# на заказы ссылаются платежи, отзывы в MongoDB и трек курьера в Cassandra, на доставки — трек.
#
# Устроен так же, как перенос пользователей (в истории git — identity-from-monolith.sh):
#   - ждёт, пока все три схемы появятся;
#   - переносит в каждую базу, только если она ещё пуста;
#   - каждая база — в одной транзакции и со сдвигом последовательностей.
#
# Запускать при остановленных монолите и сервисах заказов и платежей: заказ, созданный
# посреди переноса, потерялся бы между базами. Повторный запуск безопасен:
#   docker compose --profile full up orders-data-migration
set -eu

MONOLITH_DB="$MONOLITH_DB_NAME"
MONOLITH_SCHEMA="${MONOLITH_SCHEMA:-food_app}"
ORDERS_DB="$ORDER_DB_NAME"
PAYMENTS_DB="$PAYMENT_DB_NAME"
WAIT_SECONDS="${WAIT_SECONDS:-600}"

table_exists() {
    # $1 — база, $2 — схема, $3 — таблица
    psql -d "$1" -tAqc "SELECT to_regclass('$2.$3') IS NOT NULL" | grep -q t
}

echo "Жду схемы монолита ($MONOLITH_DB.$MONOLITH_SCHEMA), заказов ($ORDERS_DB) и платежей ($PAYMENTS_DB)..."

waited=0
until table_exists "$MONOLITH_DB" "$MONOLITH_SCHEMA" customer_order \
        && table_exists "$ORDERS_DB" public processed_message \
        && table_exists "$PAYMENTS_DB" public processed_message; do
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
        echo "Схемы не появились за ${WAIT_SECONDS} с. Запустите монолит, order-service и payment-service,"
        echo "затем повторите: docker compose --profile full up orders-data-migration"
        exit 0
    fi
    sleep 2
    waited=$((waited + 2))
done

DUMP_DIR=$(mktemp -d)
trap 'rm -rf "$DUMP_DIR"' EXIT

# --- Заказы ------------------------------------------------------------------------------------

orders_rows=$(psql -d "$ORDERS_DB" -tAqc "SELECT COUNT(*) FROM customer_order")

if [ "$orders_rows" -ne 0 ]; then
    echo "Заказы уже есть ($orders_rows) — перенос заказов выполнен, пропускаю"
else
    # Способ оплаты и ключ идемпотентности берутся из успешного платежа: в монолите они жили
    # в платеже, а в сервисе заказов — в самом заказе.
    #
    # Из outbox переносятся только неотправленные события: опубликованные уже прочитаны
    # потребителями, а повторная отправка разослала бы уведомления второй раз.
    psql -d "$MONOLITH_DB" -v ON_ERROR_STOP=1 -q <<SQL
\copy (SELECT id, customer_id, restaurant_id, status, created_at, updated_at FROM ${MONOLITH_SCHEMA}.cart) TO '${DUMP_DIR}/cart.csv' CSV
\copy (SELECT id, cart_id, menu_item_id, menu_item_name, quantity, price, created_at, updated_at FROM ${MONOLITH_SCHEMA}.cart_item) TO '${DUMP_DIR}/cart_item.csv' CSV
\copy (SELECT o.id, o.cart_id, o.customer_id, o.restaurant_id, o.status, o.total_amount, p.method, p.idempotency_key, o.created_at, o.updated_at FROM ${MONOLITH_SCHEMA}.customer_order o LEFT JOIN ${MONOLITH_SCHEMA}.payment p ON p.order_id = o.id AND p.status IN ('SUCCEEDED', 'REFUNDED')) TO '${DUMP_DIR}/customer_order.csv' CSV
\copy (SELECT id, order_id, menu_item_id, menu_item_name, quantity, price, line_total, created_at FROM ${MONOLITH_SCHEMA}.customer_order_item) TO '${DUMP_DIR}/customer_order_item.csv' CSV
\copy (SELECT id, order_id, event_type, description, created_at FROM ${MONOLITH_SCHEMA}.customer_order_event) TO '${DUMP_DIR}/customer_order_event.csv' CSV
\copy (SELECT id, order_id, courier_id, status, created_at, updated_at, picked_up_at, delivered_at FROM ${MONOLITH_SCHEMA}.delivery) TO '${DUMP_DIR}/delivery.csv' CSV
\copy (SELECT id, actor_id, actor_email, action, resource_type, resource_id, outcome, details, correlation_id, created_at FROM ${MONOLITH_SCHEMA}.audit_log) TO '${DUMP_DIR}/audit_log.csv' CSV
\copy (SELECT event_id, 'food-delivery.order-events', aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at FROM ${MONOLITH_SCHEMA}.order_event_outbox WHERE status = 'PENDING') TO '${DUMP_DIR}/outbox.csv' CSV
SQL

    psql -d "$ORDERS_DB" -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;

LOCK TABLE cart, cart_item, customer_order, customer_order_item, customer_order_event, delivery,
    audit_log, order_event_outbox IN EXCLUSIVE MODE;

DO \$\$
BEGIN
    IF EXISTS (SELECT 1 FROM customer_order) OR EXISTS (SELECT 1 FROM cart) THEN
        RAISE EXCEPTION 'Корзины или заказы появились во время переноса — перенос отменён';
    END IF;
END
\$\$;

\copy cart (id, customer_id, restaurant_id, status, created_at, updated_at) FROM '${DUMP_DIR}/cart.csv' CSV
\copy cart_item (id, cart_id, menu_item_id, menu_item_name, quantity, price, created_at, updated_at) FROM '${DUMP_DIR}/cart_item.csv' CSV
\copy customer_order (id, cart_id, customer_id, restaurant_id, status, total_amount, payment_method, payment_idempotency_key, created_at, updated_at) FROM '${DUMP_DIR}/customer_order.csv' CSV
\copy customer_order_item (id, order_id, menu_item_id, menu_item_name, quantity, price, line_total, created_at) FROM '${DUMP_DIR}/customer_order_item.csv' CSV
\copy customer_order_event (id, order_id, event_type, description, created_at) FROM '${DUMP_DIR}/customer_order_event.csv' CSV
\copy delivery (id, order_id, courier_id, status, created_at, updated_at, picked_up_at, delivered_at) FROM '${DUMP_DIR}/delivery.csv' CSV
\copy audit_log (id, actor_id, actor_email, action, resource_type, resource_id, outcome, details, correlation_id, created_at) FROM '${DUMP_DIR}/audit_log.csv' CSV
\copy order_event_outbox (event_id, topic, aggregate_type, aggregate_id, event_type, payload, correlation_id, created_at) FROM '${DUMP_DIR}/outbox.csv' CSV

-- Без сдвига последовательности выдали бы номера, уже занятые перенесёнными строками.
\o /dev/null
SELECT setval(pg_get_serial_sequence('cart', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM cart;
SELECT setval(pg_get_serial_sequence('cart_item', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM cart_item;
SELECT setval(pg_get_serial_sequence('customer_order', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customer_order;
SELECT setval(pg_get_serial_sequence('customer_order_item', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customer_order_item;
SELECT setval(pg_get_serial_sequence('customer_order_event', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM customer_order_event;
SELECT setval(pg_get_serial_sequence('delivery', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM delivery;
SELECT setval(pg_get_serial_sequence('audit_log', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM audit_log;
\o

COMMIT;
SQL

    psql -d "$ORDERS_DB" -tAqc "SELECT 'Перенесено: корзин ' || (SELECT COUNT(*) FROM cart) || ', заказов ' || (SELECT COUNT(*) FROM customer_order) || ', доставок ' || (SELECT COUNT(*) FROM delivery) || ', неотправленных событий ' || (SELECT COUNT(*) FROM order_event_outbox)"
fi

# --- Платежи -----------------------------------------------------------------------------------

payments_rows=$(psql -d "$PAYMENTS_DB" -tAqc "SELECT COUNT(*) FROM payment")

if [ "$payments_rows" -ne 0 ]; then
    echo "Платежи уже есть ($payments_rows) — перенос платежей выполнен, пропускаю"
else
    psql -d "$MONOLITH_DB" -v ON_ERROR_STOP=1 -q <<SQL
\copy (SELECT id, order_id, customer_id, amount, status, method, idempotency_key, failure_reason, created_at, updated_at FROM ${MONOLITH_SCHEMA}.payment) TO '${DUMP_DIR}/payment.csv' CSV
SQL

    psql -d "$PAYMENTS_DB" -v ON_ERROR_STOP=1 -q <<SQL
BEGIN;

LOCK TABLE payment IN EXCLUSIVE MODE;

DO \$\$
BEGIN
    IF EXISTS (SELECT 1 FROM payment) THEN
        RAISE EXCEPTION 'Платежи появились во время переноса — перенос отменён';
    END IF;
END
\$\$;

\copy payment (id, order_id, customer_id, amount, status, method, idempotency_key, failure_reason, created_at, updated_at) FROM '${DUMP_DIR}/payment.csv' CSV

\o /dev/null
SELECT setval(pg_get_serial_sequence('payment', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM payment;
\o

COMMIT;
SQL

    psql -d "$PAYMENTS_DB" -tAqc "SELECT 'Перенесено платежей: ' || COUNT(*) FROM payment"
fi
