#!/usr/bin/env bash
# Клонирует репозитории контрактов и всех сервисов рядом друг с другом.
#
#   scripts/clone-services.sh [каталог]     # по умолчанию — родитель этого репозитория
#
# Уже склонированное пропускается, так что скрипт можно запускать повторно.
set -euo pipefail

TARGET="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
OWNER="${GITHUB_OWNER:-Dima77726}"

REPOS=(
  contracts
  api-gateway identity-service catalog-service order-service
  payment-service notification-service tracking-service insights-service
)

mkdir -p "$TARGET"

for name in "${REPOS[@]}"; do
  dir="$TARGET/food-delivery-$name"

  if [ -d "$dir/.git" ]; then
    echo "уже есть: $dir"
  else
    git clone "https://github.com/$OWNER/food-delivery-$name.git" "$dir"
  fi
done
