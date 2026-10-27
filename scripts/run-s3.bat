@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0.."

REM The sandbox injects SERVER__PORT, and Spring's relaxed binding treats it as
REM server.port with higher precedence than application.yml. Override it here.
REM WeType (input method) holds 0.0.0.0:8080 on this machine, so run:
REM     set FS_PORT=18080
REM before this script when 8080 cannot be bound.
if not defined FS_PORT set FS_PORT=8080
set SERVER__PORT=%FS_PORT%

REM This one line is the whole difference between the two backends.
REM No service, mapper or controller code changes - that is the M2 acceptance
REM criterion in practice.
set STORAGE_TYPE=s3

set S3_ENDPOINT=http://127.0.0.1:9090
set S3_BUCKET=file-storage
set S3_ACCESS_KEY=test
set S3_SECRET_KEY=test

echo Starting file-storage (STORAGE_TYPE=s3) on http://localhost:%FS_PORT% ...
echo S3 endpoint: %S3_ENDPOINT%  bucket: %S3_BUCKET%
echo Prerequisite: D:\Codex\s3mock-runner\start.bat must be running
echo.

call mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=%FS_PORT%
