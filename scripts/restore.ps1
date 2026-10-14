<#
.SYNOPSIS
    Восстанавливает данные платформы из копии, снятой scripts/backup.ps1.

.DESCRIPTION
    ЗАМЕНЯЕТ текущие данные PostgreSQL (все базы сервисов), фото блюд и Keycloak данными из копии.
    Перед этим сервисы приложения останавливаются, после восстановления запускаются снова.
    Без ключа -Force скрипт спрашивает подтверждение. Отзывы (Mongo) восстанавливаются, если они есть в копии.

.EXAMPLE
    ./scripts/restore.ps1 -Path backups\2026-10-14_21-00-00
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [switch]$Force
)

# Docker пишет предупреждения в stderr; результат проверяется по коду выхода, а не по потоку ошибок.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

$dir = (Resolve-Path -LiteralPath $Path).Path
$sql = Join-Path $dir 'postgres.sql'
if (-not (Test-Path -LiteralPath $sql)) { throw "В $dir нет postgres.sql: это не копия платформы." }

if (-not $Force) {
    Write-Host "Будут ЗАМЕНЕНЫ текущие данные платформы данными из копии:`n  $dir" -ForegroundColor Yellow
    $answer = Read-Host 'Продолжить? Введите "да"'
    if ($answer -ne 'да') { Write-Host 'Отменено.'; exit 1 }
}

cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Docker не запущен.' }

Write-Host 'Останавливаю сервисы приложения (базы остаются)…'
$apps = 'api-gateway', 'catalog-service', 'identity-service', 'order-service', 'payment-service', 'notification-service', 'tracking-service', 'insights-service'
docker compose --profile full stop @apps | Out-Null

function Restore-Volume($suffix, $file) {
    $archive = Join-Path $dir $file
    if (-not (Test-Path -LiteralPath $archive)) { return }
    $volume = docker volume ls --format '{{.Name}}' | Where-Object { $_ -like "*_$suffix" } | Select-Object -First 1
    if (-not $volume) { Write-Host "  – тома $suffix нет, пропускаю" -ForegroundColor DarkGray; return }
    Write-Host "Том $suffix…"
    docker run --rm -v "${volume}:/data" -v "${dir}:/backup:ro" postgres:16 sh -c "rm -rf /data/* /data/.[!.]* 2>/dev/null; tar xzf /backup/$file -C /data"
    if ($LASTEXITCODE -ne 0) { throw "Не удалось восстановить том $volume." }
}

Write-Host 'PostgreSQL…'
cmd /c "docker exec -i food-delivery-postgres sh -c `"psql -U \`"`$POSTGRES_USER\`" -d postgres -q -v ON_ERROR_STOP=0`" < `"$sql`" > NUL 2> `"$dir\restore-errors.log`""
$errors = if (Test-Path -LiteralPath "$dir\restore-errors.log") { (Get-Content -LiteralPath "$dir\restore-errors.log" | Select-String 'ERROR' | Where-Object { $_ -notmatch 'does not exist|already exists|cannot drop the currently open database|current user cannot be dropped' }).Count } else { 0 }
if ($errors -gt 0) { Write-Host "  ! при восстановлении были ошибки ($errors), см. $dir\restore-errors.log" -ForegroundColor Yellow } else { Write-Host '  ✓ базы восстановлены' -ForegroundColor Green }

Restore-Volume 'food_delivery_minio_data' 'minio-data.tar.gz'
Restore-Volume 'food_delivery_keycloak_data' 'keycloak-data.tar.gz'

$mongoArchive = Join-Path $dir 'mongo.archive.gz'
if ((Test-Path -LiteralPath $mongoArchive) -and ((docker ps --filter 'name=^food-delivery-mongo$' --format '{{.Names}}') -eq 'food-delivery-mongo')) {
    Write-Host 'Отзывы (Mongo)…'
    cmd /c "docker exec -i food-delivery-mongo mongorestore --archive --gzip --drop --quiet < `"$mongoArchive`""
}

Write-Host 'Запускаю сервисы…'
docker compose --profile full up -d | Out-Null
Write-Host 'Готово. Файл .env из копии не применялся: при необходимости возьмите его из env.backup вручную.' -ForegroundColor Cyan
