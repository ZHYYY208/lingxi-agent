$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$port = 8000
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    Write-Host "Stopping Lingxi server (PID $($listener.OwningProcess))..."
    Stop-Process -Id $listener.OwningProcess -Force
    Start-Sleep -Seconds 2
}

Push-Location backend
try {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        Write-Host "Building with Maven..."
        & mvn -q -DskipTests package
    } else {
        Write-Host "Building with Maven Wrapper..."
        & .\mvnw.cmd -q -DskipTests package
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$logDir = Join-Path $PSScriptRoot "data"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$proc = Start-Process -FilePath "java" `
    -ArgumentList "-jar", "backend\target\lingxi-backend.jar" `
    -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput (Join-Path $logDir "server.log") `
    -RedirectStandardError (Join-Path $logDir "server.err.log") `
    -WindowStyle Hidden `
    -PassThru

Write-Host "Started Lingxi server (PID $($proc.Id)). Waiting for health check..."
Start-Sleep -Seconds 8
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 10
    Write-Host "Service is $($health.status): http://127.0.0.1:$port"
    Write-Host "Admin console: http://127.0.0.1:$port/admin"
} catch {
    Write-Host "Build succeeded, but the health check failed. See data\server.log." -ForegroundColor Yellow
}
