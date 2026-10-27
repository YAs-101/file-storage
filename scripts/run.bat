@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

REM 沙箱会注入 SERVER__PORT=3137，Spring 的 relaxed binding 会把它当成 server.port，
REM 优先级高于 application.yml，导致端口冲突。这里显式覆盖回 FS_PORT。
REM 本机微信输入法的 wetype_renderer.exe 会绑 0.0.0.0:8080，8080 被占时用
REM   set FS_PORT=18080 后再运行本脚本，测试脚本配合 FS_BASE 即可
if not defined FS_PORT set FS_PORT=8080
set SERVER__PORT=%FS_PORT%

echo Starting file-storage on http://localhost:%FS_PORT% ...
echo API: POST /api/upload/init   GET /api/health
echo.

call mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=%FS_PORT%
