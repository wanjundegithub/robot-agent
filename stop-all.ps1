$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root "output\runtime"
$pidFile = Join-Path $logDir "pids.json"

function Stop-IfRunning {
    param([int]$ProcessId)
    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        Stop-Process -Id $process.Id -Force -ErrorAction Stop
        Write-Host "Stopped PID $ProcessId"
    } catch {
    }
}

if (Test-Path $pidFile) {
    $payload = Get-Content -Path $pidFile -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($serviceName in @("frontend", "python", "java")) {
        $service = $payload.services.$serviceName
        if ($service -and $service.pid) {
            Stop-IfRunning -ProcessId ([int]$service.pid)
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped services recorded in pids.json"
}

foreach ($port in 5173, 8000, 8080, 8091) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        Stop-IfRunning -ProcessId ([int]$listener.OwningProcess)
    }
}

Write-Host "Stopped any listeners found on 5173, 8000, 8080, 8091"
