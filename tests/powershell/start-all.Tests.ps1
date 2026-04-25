$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptPath = Join-Path $repoRoot "start-all.ps1"

Describe "start-all.ps1" {
    It "reports missing uvicorn instead of timing out" {
        $tempRoot = Join-Path $env:TEMP ("robot-agent-start-all-test-" + [guid]::NewGuid().ToString("N"))
        $stdoutPath = Join-Path $tempRoot "stdout.log"
        $stderrPath = Join-Path $tempRoot "stderr.log"

        New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

        try {
            $process = Start-Process -FilePath "powershell.exe" `
                -ArgumentList @(
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File", $scriptPath,
                    "-SkipJava",
                    "-SkipFrontend",
                    "-SkipMiddleware"
                ) `
                -RedirectStandardOutput $stdoutPath `
                -RedirectStandardError $stderrPath `
                -PassThru

            $finished = $process.WaitForExit(15000)
            $finished | Should Be $true

            $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Raw } else { "" }
            $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath -Raw } else { "" }
            $combined = ($stdout, $stderr) -join "`n"

            $combined | Should Match "uvicorn"
            $combined | Should Not Match "python-ai did not open port 8000 in time"
        }
        finally {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
