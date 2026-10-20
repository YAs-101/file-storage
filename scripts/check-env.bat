@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
set "ROOT=%~dp0.."
cd /d "%ROOT%"

echo ============================================
echo  file-storage  dev environment check
echo ============================================
echo.

set FAILED=0

echo [1/4] Redis 6379 ...
"D:\Tools\redis\redis-cli.exe" ping >"%TEMP%\fs_redis.txt" 2>&1
findstr /C:"PONG" "%TEMP%\fs_redis.txt" >nul
if errorlevel 1 (
    echo    FAIL  Redis not responding. Start it with:
    echo          D:\Tools\redis\redis-server.exe --service-start
    set FAILED=1
) else (
    echo    OK    PONG
)
echo.

echo [2/4] MySQL file_storage tables ...
mysql -u root -p123456 -N -B file_storage -e "select count(*) from information_schema.tables where table_schema='file_storage';" >"%TEMP%\fs_mysql.txt" 2>nul
if errorlevel 1 (
    echo    FAIL  cannot connect MySQL as root
    set FAILED=1
) else (
    set /p TBLCNT=<"%TEMP%\fs_mysql.txt"
    if "!TBLCNT!"=="4" (
        echo    OK    4 tables
    ) else (
        echo    FAIL  expected 4 tables, got !TBLCNT!
        echo          mysql -u root -p123456 ^< sql\schema.sql
        set FAILED=1
    )
)
echo.

echo [3/4] Maven offline resolve ...
call mvn -f scripts\prefetch-pom.xml -o -q dependency:resolve >"%TEMP%\fs_mvn.txt" 2>&1
if errorlevel 1 (
    echo    FAIL  offline resolve failed, run without -o to refill cache
    set FAILED=1
) else (
    echo    OK    all dependencies cached
)
echo.

echo [4/4] Port 8080 free ...
netstat -ano | findstr /R /C:"LISTENING.*:8080 " >nul
if errorlevel 1 (
    echo    OK    port 8080 is free
) else (
    echo    WARN  port 8080 already in use, change server.port
)
echo.

echo --------------------------------------------
if "%FAILED%"=="1" (
    echo  RESULT: NOT READY - fix items marked FAIL
) else (
    echo  RESULT: READY - start coding
)
echo --------------------------------------------
del "%TEMP%\fs_redis.txt" "%TEMP%\fs_mysql.txt" "%TEMP%\fs_mvn.txt" >nul 2>&1
pause
