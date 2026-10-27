@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

REM 沙箱会注入 SERVER__PORT，Spring 的 relaxed binding 会把它当成 server.port，
REM 优先级高于 application.yml，导致端口冲突。这里显式覆盖回 FS_PORT。
REM 本机微信输入法会绑 0.0.0.0:8080，被占时先 set FS_PORT=18080
if not defined FS_PORT set FS_PORT=8080
set SERVER__PORT=%FS_PORT%

REM 唯一的差别就是这一行。业务代码、Mapper、Controller 一行都不用改，
REM 这是 M2 验收标准「换存储后端不改业务代码」的直接证据。
set STORAGE_TYPE=s3

set S3_ENDPOINT=http://127.0.0.1:9090
set S3_BUCKET=file-storage
set S3_ACCESS_KEY=test
set S3_SECRET_KEY=test

echo Starting file-storage (STORAGE_TYPE=s3) on http://localhost:%FS_PORT% ...
echo S3 endpoint: %S3_ENDPOINT%  bucket: %S3_BUCKET%
echo 前置条件: D:\Codex\s3mock-runner\start.bat 已在运行
echo.

call mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=%FS_PORT%
