$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Stopping local app processes..."
& (Join-Path $root "stop-all.ps1")

Write-Host "Stopping middleware containers..."
docker compose -f (Join-Path $root "docker-compose.phase2.yml") down -v

Write-Host "Starting middleware containers..."
docker compose -f (Join-Path $root "docker-compose.phase2.yml") up -d --remove-orphans

Write-Host "Waiting for MySQL..."
$deadline = (Get-Date).AddMinutes(2)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        $state = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}" robot-agent-mysql 2>$null
        if ($state -eq "healthy") {
            Start-Sleep -Seconds 5
            $mysqlReadyCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -e ''SELECT 1'' robot_agent'
            docker exec robot-agent-mysql sh -lc $mysqlReadyCommand | Out-Null
            $ready = $true
            break
        }
    } catch {
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    throw "MySQL did not become healthy in time."
}

Write-Host "Resetting MySQL schema..."
$mysqlResetCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -e ''DROP DATABASE IF EXISTS robot_agent; CREATE DATABASE robot_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'''
docker exec robot-agent-mysql sh -lc $mysqlResetCommand

Write-Host "Setting runtime environment..."
$env:ROBOT_DB_URL = 'jdbc:mysql://localhost:3306/robot_agent?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
$env:ROBOT_DB_USERNAME = 'root'
$env:ROBOT_DB_PASSWORD = 'root'
$env:ROBOT_REDIS_URL = 'redis://localhost:6379/0'
$env:ROBOT_VECTOR_DSN = 'postgresql://robot:robot@localhost:5432/robot_vector'
$env:ROBOT_OTEL_ENABLED = 'false'

Write-Host "Starting application stack..."
& (Join-Path $root "start-all.ps1")
