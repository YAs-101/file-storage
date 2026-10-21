@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

REM 沙箱会注入 SERVER__PORT=3137，Spring 的 relaxed binding 会把它当成 server.port，
REM 优先级高于 application.yml，导致端口冲突。这里显式覆盖回 8080。
set SERVER__PORT=8080

echo Starting file-storage on http://localhost:8080 ...
echo API: POST /api/upload/init   GET /api/health
echo.

call mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
