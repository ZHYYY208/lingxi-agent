$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path "backend\target\lingxi-backend.jar")) {
    Write-Host "Building Lingxi Spring Boot backend..."
    & .\mvnw.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Host "Starting Lingxi at http://127.0.0.1:8000"
java -jar backend\target\lingxi-backend.jar
