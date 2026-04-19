param(
    [switch]$SkipPython,
    [switch]$SkipJava,
    [switch]$SkipFrontend,
    [switch]$SkipMiddleware
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root "output\runtime"
$pidFile = Join-Path $logDir "pids.json"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Assert-PortFree {
    param(
        [int[]]$Ports,
        [string]$ServiceName
    )
    foreach ($port in $Ports) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            throw "Port $port is already in use for $ServiceName. Stop the existing process first."
        }
    }
}

function Wait-ForPort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Assert-MiddlewarePort {
    param(
        [int]$Port,
        [string]$Name
    )
    if (-not (Wait-ForPort -Port $Port -TimeoutSeconds 5)) {
        throw "$Name is not available on port $Port. Start middleware first."
    }
}

function Wait-ForMySqlReady {
    param(
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $state = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}" robot-agent-mysql 2>$null
            if ($state -eq "healthy") {
                Start-Sleep -Seconds 5
                $mysqlReadyCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -e ''SELECT 1'' robot_agent'
                docker exec robot-agent-mysql sh -lc $mysqlReadyCommand | Out-Null
                return $true
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

$started = @()

try {
    $services = [ordered]@{}

    if (-not $SkipMiddleware) {
        Assert-Command "docker"
        docker compose -f (Join-Path $root "docker-compose.phase2.yml") up -d --remove-orphans | Out-Null
        Assert-MiddlewarePort -Port 6379 -Name "Redis"
        Assert-MiddlewarePort -Port 5432 -Name "pgvector"
        if (-not (Wait-ForMySqlReady -TimeoutSeconds 180)) {
            throw "MySQL is not ready for SQL connections."
        }
    }

    $env:ROBOT_DB_URL = 'jdbc:mysql://localhost:3306/robot_agent?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
    $env:ROBOT_DB_USERNAME = 'root'
    $env:ROBOT_DB_PASSWORD = 'root'
    $env:ROBOT_REDIS_URL = 'redis://localhost:6379/0'
    $env:ROBOT_VECTOR_DSN = 'postgresql://robot:robot@localhost:5432/robot_vector'
    $env:ROBOT_OTEL_ENABLED = 'false'

    if (-not $env:ROBOT_LLM_API_KEY) {
        Write-Warning "ROBOT_LLM_API_KEY is not set. Seeded demo Provider tests will fail until you enter a real API Key in the model config page or set the environment variable."
    }

    if (-not $SkipPython) {
        Assert-Command "python"
        Assert-PortFree -Ports @(8000) -ServiceName "python-ai"

        $pythonOut = Join-Path $logDir "python.out.log"
        $pythonErr = Join-Path $logDir "python.err.log"
        $python = Start-Process -FilePath "python" `
            -ArgumentList @("-m", "uvicorn", "src.api.main:app", "--host", "127.0.0.1", "--port", "8000") `
            -WorkingDirectory (Join-Path $root "python-ai") `
            -RedirectStandardOutput $pythonOut `
            -RedirectStandardError $pythonErr `
            -PassThru
        $started += $python.Id
        $services.python = @{
            pid = $python.Id
            port = 8000
            health = "http://127.0.0.1:8000/health"
        }
    }

    if (-not $SkipJava) {
        Assert-Command "mvn"
        Assert-PortFree -Ports @(8080, 8091) -ServiceName "java-backend"

        $javaOut = Join-Path $logDir "java.out.log"
        $javaErr = Join-Path $logDir "java.err.log"
        $java = Start-Process -FilePath "mvn" `
            -ArgumentList @("-Dspring-boot.run.profiles=local-e2e", "spring-boot:run") `
            -WorkingDirectory (Join-Path $root "java-backend") `
            -RedirectStandardOutput $javaOut `
            -RedirectStandardError $javaErr `
            -PassThru
        $started += $java.Id
        $services.java = @{
            pid = $java.Id
            ports = @(8080, 8091)
            health = "http://127.0.0.1:8080/actuator/health"
            websocket = "ws://127.0.0.1:8091/ws/robot"
        }
    }

    if (-not $SkipFrontend) {
        Assert-Command "cmd.exe"
        Assert-PortFree -Ports @(5173) -ServiceName "frontend"

        $frontOut = Join-Path $logDir "frontend.out.log"
        $frontErr = Join-Path $logDir "frontend.err.log"
        $frontCmd = "set VITE_NETTY_WS_BASE_URL=ws://127.0.0.1:8091&& npm run dev -- --host 127.0.0.1 --port 5173"
        $frontend = Start-Process -FilePath "cmd.exe" `
            -ArgumentList @("/c", $frontCmd) `
            -WorkingDirectory (Join-Path $root "frontend") `
            -RedirectStandardOutput $frontOut `
            -RedirectStandardError $frontErr `
            -PassThru
        $started += $frontend.Id
        $services.frontend = @{
            pid = $frontend.Id
            port = 5173
            url = "http://127.0.0.1:5173/"
        }
    }

    if ($services.python) {
        if (-not (Wait-ForPort -Port 8000 -TimeoutSeconds 60)) {
            throw "python-ai did not open port 8000 in time."
        }
    }

    if ($services.java) {
        if (-not (Wait-ForPort -Port 8080 -TimeoutSeconds 90)) {
            throw "java-backend did not open port 8080 in time."
        }
        if (-not (Wait-ForPort -Port 8091 -TimeoutSeconds 90)) {
            throw "java-backend did not open port 8091 in time."
        }
    }

    if ($services.frontend) {
        if (-not (Wait-ForPort -Port 5173 -TimeoutSeconds 60)) {
            throw "frontend did not open port 5173 in time."
        }
    }

    $payload = [ordered]@{
        started_at = (Get-Date).ToString("s")
        root = $root
        services = $services
    }
    $payload | ConvertTo-Json -Depth 8 | Set-Content -Path $pidFile -Encoding utf8

    Write-Host ""
    Write-Host "All components started."
    if ($services.frontend) { Write-Host "Frontend: http://127.0.0.1:5173/" }
    if ($services.python) { Write-Host "Python:   http://127.0.0.1:8000/health" }
    if ($services.java) {
        Write-Host "Java:     http://127.0.0.1:8080/actuator/health"
        Write-Host "Gateway:  ws://127.0.0.1:8091/ws/robot"
    }
    Write-Host "Logs:     $logDir"
} catch {
    foreach ($processId in $started) {
        try {
            Stop-Process -Id $processId -Force -ErrorAction Stop
        } catch {
        }
    }
    throw
}
