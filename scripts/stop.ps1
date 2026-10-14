<#
.SYNOPSIS
    Останавливает платформу, ИИ-помощника и Ollama. Данные сохраняются.

.DESCRIPTION
    Контейнеры именно останавливаются (docker compose stop), а не удаляются, и тома с данными не трогаются.
    -Down удаляет контейнеры (данные в томах остаются). Тома этот скрипт не удаляет никогда.
#>
param([switch]$Down)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

foreach ($name in 'agent', 'ollama') {
    $pidFile = Join-Path $Root ".run\$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $id = [int](Get-Content -LiteralPath $pidFile)
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process) { Stop-Process -Id $id -Force; Write-Host "Остановлен процесс: $name" }
        Remove-Item -LiteralPath $pidFile
    }
}

$all = @('--profile', 'full', '--profile', 'stores', '--profile', 'auth', '--profile', 'tools')
if ($Down) {
    docker compose @all down
    Write-Host 'Контейнеры удалены, данные сохранены в томах Docker.'
} else {
    docker compose @all stop
    Write-Host 'Платформа остановлена. Запуск: ./scripts/start.ps1'
}
