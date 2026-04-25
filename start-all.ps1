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

function Resolve-PythonCommand {
    $venvPython = Join-Path $root "python-ai\.venv\Scripts\python.exe"
    if (Test-Path -LiteralPath $venvPython) {
        return $venvPython
    }
    Assert-Command "python"
    return "python"
}

function Invoke-ProcessCapture {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )

    $tempRoot = Join-Path $env:TEMP ("robot-agent-process-" + [guid]::NewGuid().ToString("N"))
    $stdoutPath = Join-Path $tempRoot "stdout.log"
    $stderrPath = Join-Path $tempRoot "stderr.log"

    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    try {
        $process = Start-Process -FilePath $FilePath `
            -ArgumentList $ArgumentList `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru `
            -Wait

        $stdout = if (Test-Path -LiteralPath $stdoutPath) { [string](Get-Content -LiteralPath $stdoutPath -Raw) } else { "" }
        $stderr = if (Test-Path -LiteralPath $stderrPath) { [string](Get-Content -LiteralPath $stderrPath -Raw) } else { "" }

        return @{
            ExitCode = $process.ExitCode
            StdOut = if ($stdout) { $stdout.Trim() } else { "" }
            StdErr = if ($stderr) { $stderr.Trim() } else { "" }
        }
    }
    finally {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Assert-PythonModule {
    param(
        [string]$PythonCommand,
        [string]$ModuleName
    )

    $result = Invoke-ProcessCapture -FilePath $PythonCommand -ArgumentList @("-c `"import $ModuleName`"")
    if ($result.ExitCode -eq 0) {
        return
    }

    $details = @($result.StdErr, $result.StdOut) | Where-Object { $_ } | Select-Object -First 1
    $hint = "$PythonCommand -m pip install -r python-ai/requirements.txt"
    if ($details) {
        throw "python-ai is missing Python module '$ModuleName'. Run '$hint'. Details: $details"
    }
    throw "python-ai is missing Python module '$ModuleName'. Run '$hint'."
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

function Get-LogExcerpt {
    param(
        [string]$Path,
        [int]$Tail = 20
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

    $lines = Get-Content -LiteralPath $Path -Tail $Tail -ErrorAction SilentlyContinue
    if (-not $lines) {
        return $null
    }

    return (($lines | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
}

function Wait-ForServicePort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds,
        [System.Diagnostics.Process]$Process,
        [string]$ServiceName,
        [string]$ErrorLogPath
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener) {
            return $true
        }

        if ($Process) {
            $Process.Refresh()
            if ($Process.HasExited) {
                $details = Get-LogExcerpt -Path $ErrorLogPath
                if ($details) {
                    throw "$ServiceName exited before opening port $Port (exit code $($Process.ExitCode)). $details"
                }
                throw "$ServiceName exited before opening port $Port (exit code $($Process.ExitCode)). See $ErrorLogPath for details."
            }
        }

        Start-Sleep -Milliseconds 500
    }

    $details = Get-LogExcerpt -Path $ErrorLogPath
    if ($details) {
        throw "$ServiceName did not open port $Port in time. Last error: $details"
    }
    throw "$ServiceName did not open port $Port in time."
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
        docker compose -f (Join-Path $root "docker-compose.yml") up -d --remove-orphans | Out-Null
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
        $pythonCommand = Resolve-PythonCommand
        Assert-PythonModule -PythonCommand $pythonCommand -ModuleName "uvicorn"
        Assert-PortFree -Ports @(8000) -ServiceName "python-ai"

        $pythonOut = Join-Path $logDir "python.out.log"
        $pythonErr = Join-Path $logDir "python.err.log"
        $python = Start-Process -FilePath $pythonCommand `
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
        Wait-ForServicePort -Port 8000 -TimeoutSeconds 60 -Process $python -ServiceName "python-ai" -ErrorLogPath $pythonErr
    }

    if ($services.java) {
        Wait-ForServicePort -Port 8080 -TimeoutSeconds 90 -Process $java -ServiceName "java-backend" -ErrorLogPath $javaErr
        Wait-ForServicePort -Port 8091 -TimeoutSeconds 90 -Process $java -ServiceName "java-backend" -ErrorLogPath $javaErr
    }

    if ($services.frontend) {
        Wait-ForServicePort -Port 5173 -TimeoutSeconds 60 -Process $frontend -ServiceName "frontend" -ErrorLogPath $frontErr
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
