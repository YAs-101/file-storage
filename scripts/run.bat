@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

REM The sandbox injects SERVER__PORT (value differs every session), and Spring's
REM relaxed binding treats it as server.port with higher precedence than
REM application.yml. Override it here or the app fights for the wrong port.
REM WeType (input method) holds 0.0.0.0:8080 on this machine since 2026-10-27,
REM so run "set FS_PORT=18080" first when 8080 cannot be bound, and pair it with
REM "set FS_BASE=http://127.0.0.1:18080" for the test scripts.
if not defined FS_PORT set FS_PORT=8080
set SERVER__PORT=%FS_PORT%

echo Starting file-storage on http://localhost:%FS_PORT% ...
echo API: POST /api/upload/init   GET /api/health
echo.

call mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=%FS_PORT%
