<#
.SYNOPSIS
    Копия данных платформы: все базы PostgreSQL, фото блюд, Keycloak, отзывы (Mongo) и файл .env.

.DESCRIPTION
    Кладёт копию в backups/<дата-время>/ (папка не попадает в Git). Платформу останавливать не нужно.
    Внутри:
      postgres.sql        все базы и пользователи (pg_dumpall)
      minio-data.tar.gz   фото блюд
      keycloak-data.tar.gz   пользователи и сессии Keycloak (если он запускался)
      mongo.archive.gz    отзывы (если запущена Mongo)
      env.backup          копия .env: в ней пароли, храните копию в надёжном месте
    Не копируется то, что строится заново: поисковый индекс (Elasticsearch), граф рекомендаций (Neo4j), витрина
    аналитики (ClickHouse), метрики и логи. Трек курьера (Cassandra) тоже не копируется: это история маршрутов.

.PARAMETER Keep
    Сколько последних копий хранить (старые удаляются). По умолчанию 7.
#>
param([int]$Keep = 7)

# Docker пишет предупреждения в stderr; результат проверяется по коду выхода, а не по потоку ошибок.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Docker не запущен.' }

$stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
$dir = Join-Path $Root "backups\$stamp"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$manifest = [ordered]@{ created = (Get-Date -Format s); parts = @() }

function Running($name) { (docker ps --filter "name=^$name$" --format '{{.Names}}') -eq $name }
function Add-Part($file) {
    $path = Join-Path $dir $file
    $size = if (Test-Path -LiteralPath $path) { (Get-Item -LiteralPath $path).Length } else { 0 }
    $manifest.parts += [ordered]@{ file = $file; bytes = $size }
    Write-Host ("  ✓ {0,-22} {1,10:N1} МБ" -f $file, ($size / 1MB)) -ForegroundColor Green
}

# --- PostgreSQL: все базы сервисов одним файлом ---
if (-not (Running 'food-delivery-postgres')) { throw 'PostgreSQL не запущен: сначала ./scripts/start.ps1' }
Write-Host 'PostgreSQL…'
$sql = Join-Path $dir 'postgres.sql'
# --clean --if-exists: при восстановлении существующие объекты заменяются, а не конфликтуют.
# Через cmd: байты дампа записываются как есть. Конвейер PowerShell добавил бы BOM и заменил переводы строк.
cmd /c "docker exec food-delivery-postgres sh -c ""pg_dumpall -U `$POSTGRES_USER --clean --if-exists"" > ""$sql"""
if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $sql).Length -lt 1000) { throw 'Не удалось снять копию PostgreSQL.' }
Add-Part 'postgres.sql'

# --- Тома: tar образом PostgreSQL (он уже скачан и содержит tar) ---
function Backup-Volume($suffix, $file) {
    $volume = docker volume ls --format '{{.Name}}' | Where-Object { $_ -like "*_$suffix" } | Select-Object -First 1
    if (-not $volume) { Write-Host "  – тома $suffix нет, пропускаю" -ForegroundColor DarkGray; return }
    docker run --rm -v "${volume}:/data:ro" -v "${dir}:/backup" postgres:16 tar czf "/backup/$file" -C /data .
    if ($LASTEXITCODE -ne 0) { throw "Не удалось снять копию тома $volume." }
    Add-Part $file
}
Write-Host 'Фото блюд…'
Backup-Volume 'food_delivery_minio_data' 'minio-data.tar.gz'
Write-Host 'Keycloak…'
Backup-Volume 'food_delivery_keycloak_data' 'keycloak-data.tar.gz'

# --- Mongo: отзывы ---
if (Running 'food-delivery-mongo') {
    Write-Host 'Отзывы (Mongo)…'
    $mongo = Join-Path $dir 'mongo.archive.gz'
    cmd /c "docker exec food-delivery-mongo mongodump --archive --gzip --quiet > `"$mongo`""
    if ($LASTEXITCODE -eq 0 -and (Get-Item -LiteralPath $mongo).Length -gt 0) { Add-Part 'mongo.archive.gz' } else { Write-Host '  ! копия Mongo не снята' -ForegroundColor Yellow }
}

# --- Настройки ---
Copy-Item -LiteralPath (Join-Path $Root '.env') -Destination (Join-Path $dir 'env.backup')
Add-Part 'env.backup'

$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $dir 'manifest.json') -Encoding utf8

# --- Старые копии ---
$old = Get-ChildItem -LiteralPath (Join-Path $Root 'backups') -Directory | Sort-Object Name -Descending | Select-Object -Skip $Keep
foreach ($o in $old) { Remove-Item -LiteralPath $o.FullName -Recurse -Force; Write-Host "  удалена старая копия $($o.Name)" -ForegroundColor DarkGray }

$total = (Get-ChildItem -LiteralPath $dir | Measure-Object Length -Sum).Sum
Write-Host ("`nГотово: {0} ({1:N1} МБ). Восстановление: ./scripts/restore.ps1 -Path backups\{2}" -f $dir, ($total / 1MB), $stamp) -ForegroundColor Cyan
