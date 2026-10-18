@echo off
setlocal
cd /d "%~dp0"
if not exist "backend\target\lingxi-backend.jar" (
  echo Building Lingxi Spring Boot backend...
  call mvnw.cmd -q -DskipTests package
  if errorlevel 1 (
    exit /b 1
  )
)
echo Starting Lingxi at http://127.0.0.1:8000
java -jar backend\target\lingxi-backend.jar
