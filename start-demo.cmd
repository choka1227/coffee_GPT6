@echo off
setlocal
cd /d "%~dp0"
set "COFFEE_JAR=runtime\coffee.jar"
if not exist "%COFFEE_JAR%" set "COFFEE_JAR=backend\coffee-app\target\coffee-app-0.1.0-SNAPSHOT.jar"
if not exist "%COFFEE_JAR%" (
  echo Build the project first with scripts\build.ps1.
  pause
  exit /b 1
)
java -jar "%COFFEE_JAR%" --spring.profiles.active=dev --server.address=127.0.0.1
if errorlevel 1 pause
