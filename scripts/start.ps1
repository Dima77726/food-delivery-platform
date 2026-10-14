<#
.SYNOPSIS
    Запускает платформу «Вкусно» одной командой и открывает веб-интерфейс.

.DESCRIPTION
    Поднимает контейнеры Docker Compose, ждёт готовности шлюза, один раз заводит демо-данные
    и (по ключу -Agent) запускает ИИ-помощника: Ollama и агента food_delivery_agent на этом компьютере.

    Режимы по памяти (Docker Desktop выделяет WSL около 9 ГБ, см. README):
      по умолчанию  всё, включая хранилища отзывов, рекомендаций, аналитики и поиска  ~7 ГБ
      -Light        без Mongo, Neo4j, ClickHouse и Elasticsearch: пропадают отзывы, рекомендации,
                    аналитика и поиск, остальное работает                               ~5 ГБ
      -Auth         добавляет Keycloak (+0,7 ГБ)
      -Tools        добавляет Kafka UI и Kibana (+1,5 ГБ)

.EXAMPLE
    ./scripts/start.ps1                  # всё
    ./scripts/start.ps1 -Light -Agent    # облегчённо и с ИИ-помощником
#>
param(
    [switch]$Light,
    [switch]$Auth,
    [switch]$Tools,
    [switch]$Agent,
    [switch]$NoBrowser,
    [switch]$NoSeed,
    [int]$WebPort = 8070
)

# Docker пишет предупреждения в stderr; результат проверяется по коду выхода, а не по потоку ошибок.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root
$RunDir = Join-Path $Root '.run'
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Step($text) { Write-Host "`n▶ $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "  ✓ $text" -ForegroundColor Green }
function Warn($text) { Write-Host "  ! $text" -ForegroundColor Yellow }

function Wait-Http($url, $seconds, $label) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -lt 500) { return $true }
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -lt 500) { return $true }
        }
        Start-Sleep -Seconds 3
    }
    Warn "$label не ответил за $seconds с"
    return $false
}

# ---------- Проверки ----------
Step 'Проверяю Docker и настройки'
cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Docker не запущен. Откройте Docker Desktop, дождитесь зелёного значка и повторите.' }
if (-not (Test-Path -LiteralPath (Join-Path $Root '.env'))) {
    throw 'Нет файла .env. Скопируйте .env.example в .env и задайте пароли (см. README, раздел «Запуск»).'
}
Ok 'Docker работает, .env найден'

# ---------- Контейнеры ----------
Step 'Запускаю контейнеры (первый запуск скачивает образы и занимает несколько минут)'
$profiles = @('--profile', 'full')
if (-not $Light) { $profiles += @('--profile', 'stores') }
if ($Auth) { $profiles += @('--profile', 'auth') }
if ($Tools) { $profiles += @('--profile', 'tools') }

$stores = @('STORE_MONGO_ENABLED', 'STORE_NEO4J_ENABLED', 'STORE_CLICKHOUSE_ENABLED', 'STORE_ELASTICSEARCH_ENABLED')
$saved = @{}
foreach ($name in $stores) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    if ($Light) {
        # Значения из окружения важнее .env: облегчённый режим не требует правки файла.
        foreach ($name in $stores) { [Environment]::SetEnvironmentVariable($name, 'false') }
        Warn 'Облегчённый режим: отзывы, рекомендации, аналитика и поиск выключены'
    }
    $env:WEB_PORT = "$WebPort"
    docker compose @profiles up -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose не смог запустить контейнеры (см. сообщение выше).' }
} finally {
    foreach ($name in $stores) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}

# ---------- Готовность ----------
Step 'Жду готовности шлюза (сервисам нужна минута-две на старт)'
if (-not (Wait-Http 'http://localhost:8080/api/v1/restaurants' 240 'Шлюз')) {
    throw 'Платформа не поднялась. Смотрите: docker compose logs --tail 50 api-gateway'
}
Ok 'Шлюз отвечает'
Wait-Http "http://localhost:$WebPort/" 30 'Веб-интерфейс' | Out-Null

# ---------- Kibana: представление данных для индексов поиска ----------
if ($Tools -and -not $Light) {
    if (Wait-Http 'http://localhost:5601/api/status' 180 'Kibana') {
        # Идемпотентно: представление с тем же id перезаписывается, а не дублируется.
        $view = '{"data_view":{"id":"food-delivery","title":"food-delivery-*","name":"Food Delivery: поиск (рестораны и блюда)"},"override":true}'
        try {
            Invoke-RestMethod -Method Post -Uri 'http://localhost:5601/api/data_views/data_view' -Headers @{ 'kbn-xsrf' = 'true' } `
                -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($view)) | Out-Null
            Ok 'Kibana: представление данных food-delivery-* готово'
        } catch { Warn 'Kibana: представление данных не создано (его можно создать в Stack Management → Data Views)' }
    }
}

# ---------- Демо-данные ----------
$marker = Join-Path $Root '.demo-seeded'
if (-not $NoSeed -and -not (Test-Path -LiteralPath $marker)) {
    Step 'Заношу демо-данные: пользователей трёх ролей и четыре ресторана с меню'
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
    if ($python) {
        $env:PYTHONIOENCODING = 'utf-8'
        & $python.Source (Join-Path $Root 'scripts/seed-demo.py')
        if ($LASTEXITCODE -eq 0) { Set-Content -LiteralPath $marker -Value (Get-Date -Format s) } else { Warn 'Демо-данные не занеслись; повторите: python scripts/seed-demo.py' }
    } else {
        Warn 'Python не найден: демо-пользователей можно завести позже (python scripts/seed-demo.py) или зарегистрироваться вручную'
    }
}

# ---------- ИИ-помощник ----------
function Find-OllamaRoot {
    $candidates = @($env:OLLAMA_ROOT, (Join-Path $env:LOCALAPPDATA 'food_delivery_agent\ollama'))
    $packages = Join-Path $env:LOCALAPPDATA 'Packages'
    if (Test-Path -LiteralPath $packages) {
        $candidates += Get-ChildItem -LiteralPath $packages -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'LocalCache\Local\food_delivery_agent\ollama' }
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c 'models'))) { return $c }
    }
    return $null
}

function Find-AgentDir {
    $candidates = @($env:FD_AGENT_DIR,
        (Join-Path $HOME 'OneDrive\Документы\ChatGPT\food_delivery_agent'),
        (Join-Path $HOME 'Documents\ChatGPT\food_delivery_agent'),
        (Join-Path (Split-Path $Root -Parent) 'food_delivery_agent'))
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c 'pyproject.toml'))) { return $c }
    }
    return $null
}

if ($Agent) {
    Step 'Запускаю ИИ-помощника (Ollama на видеокарте и агент)'
    $ollamaRoot = Find-OllamaRoot
    $agentDir = Find-AgentDir
    if (-not $ollamaRoot) { Warn 'Ollama с моделью не найдена. Укажите папку: $env:OLLAMA_ROOT = "<папка с models и vX.Y.Z\ollama.exe>"' }
    if (-not $agentDir) { Warn 'Проект агента не найден. Укажите папку: $env:FD_AGENT_DIR = "<путь к food_delivery_agent>"' }

    if ($ollamaRoot -and $agentDir) {
        $exe = Get-ChildItem -LiteralPath $ollamaRoot -Directory -Filter 'v*' | Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'ollama.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        $agentPython = Join-Path $agentDir '.venv\Scripts\python.exe'
        if (-not $exe -or -not (Test-Path -LiteralPath $agentPython)) {
            Warn 'Не хватает ollama.exe или .venv агента: помощник не запущен'
        } else {
            if (-not (Wait-Http 'http://127.0.0.1:11434/api/tags' 2 'Ollama')) {
                $env:OLLAMA_HOST = '127.0.0.1:11434'
                $env:OLLAMA_MODELS = Join-Path $ollamaRoot 'models'
                $env:OLLAMA_NO_CLOUD = '1'
                $p = Start-Process -FilePath $exe -ArgumentList 'serve' -WindowStyle Hidden -PassThru `
                    -RedirectStandardOutput (Join-Path $RunDir 'ollama.log') -RedirectStandardError (Join-Path $RunDir 'ollama.err.log')
                Set-Content -LiteralPath (Join-Path $RunDir 'ollama.pid') -Value $p.Id
            }
            Ok 'Ollama запущена'

            $env:AGENT_MODE = 'ollama'; $env:OLLAMA_TIMEOUT_SECONDS = '170'; $env:AGENT_TIMEOUT_SECONDS = '180'
            $env:ORDERS_BACKEND = 'platform'; $env:AUTH_MODE = 'jwt'
            $env:JWT_JWKS_URL = 'http://localhost:8080/.well-known/jwks.json'
            $env:PLATFORM_BASE_URL = 'http://localhost:8080'
            if (-not (Wait-Http 'http://127.0.0.1:8001/health' 2 'Агент')) {
                $p = Start-Process -FilePath $agentPython -WorkingDirectory $agentDir -WindowStyle Hidden -PassThru `
                    -ArgumentList '-m', 'uvicorn', 'food_delivery_agent.main:app', '--host', '127.0.0.1', '--port', '8001', '--no-access-log' `
                    -RedirectStandardOutput (Join-Path $RunDir 'agent.log') -RedirectStandardError (Join-Path $RunDir 'agent.err.log')
                Set-Content -LiteralPath (Join-Path $RunDir 'agent.pid') -Value $p.Id
            }
            if (Wait-Http 'http://127.0.0.1:8001/health' 30 'Агент') { Ok 'Агент отвечает (первый вопрос модель обдумывает до минуты: она загружается в видеокарту)' }
        }
    }
}

# ---------- Итог ----------
Write-Host ''
Write-Host '══════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host "  Вкусно:         http://localhost:$WebPort" -ForegroundColor Green
Write-Host '  Демо-вход:      кнопки на странице входа (клиент, ресторатор, курьер, админ)'
Write-Host "  Инструменты:    http://localhost:$WebPort/#/tools   (все адреса и что где смотреть)"
Write-Host '  Grafana:        http://localhost:3000     Письма:  http://localhost:8025'
Write-Host '  RabbitMQ:       http://localhost:15672    MinIO:   http://localhost:9001'
if ($Tools) { Write-Host '  Kafka UI:       http://localhost:8090     Kibana:  http://localhost:5601' }
Write-Host '  Остановить:     ./scripts/stop.ps1          Копия данных:  ./scripts/backup.ps1'
Write-Host '══════════════════════════════════════════════════════' -ForegroundColor DarkGray

if (-not $NoBrowser) { Start-Process "http://localhost:$WebPort" }
