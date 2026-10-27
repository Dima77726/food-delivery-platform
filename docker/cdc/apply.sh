#!/bin/sh
# Применяет SQL потребителя CDC в ClickHouse. Идемпотентно: все объекты — IF NOT EXISTS.
set -e
until clickhouse-client --host clickhouse --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT 1" >/dev/null 2>&1; do
  echo "Жду ClickHouse…"; sleep 3
done
clickhouse-client --host clickhouse --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" \
  --database "$CLICKHOUSE_DB" --multiquery < /cdc.sql
echo "CDC → ClickHouse готово"
