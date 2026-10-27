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
call mvn -o -q dependency:resolve >"%TEMP%\fs_mvn.txt" 2>&1
if errorlevel 1 (
    echo    FAIL  offline resolve failed, run without -o to refill cache
    set FAILED=1
) else (
    echo    OK    all dependencies cached
)
echo.

REM Check FS_PORT instead of a hard-coded 8080: since 2026-10-27 the WeType
REM input method (wetype_renderer.exe) steals 0.0.0.0:8080 on this machine.
REM netstat is not enough here: that socket sits in "Bound" state without ever
REM reaching LISTENING, so netstat shows nothing while bind() still fails.
REM Try the actual bind instead - that is the only check that matches reality.
if not defined FS_PORT set FS_PORT=8080
echo [4/4] Port %FS_PORT% free ...
powershell -NoProfile -Command "try { $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, %FS_PORT%); $l.Start(); $l.Stop(); exit 0 } catch { exit 1 }" >nul 2>&1
if errorlevel 1 (
    echo    WARN  port %FS_PORT% cannot be bound
    echo          WeType likely holds it. Use another port:
    echo            set FS_PORT=18080                     then run run.bat / run-s3.bat
    echo            set FS_BASE=http://127.0.0.1:18080    for the test scripts
) else (
    echo    OK    port %FS_PORT% is bindable
)
echo.

echo [extra] S3 endpoint 9090 ...
netstat -ano | findstr /R /C:":9090 .*LISTENING" >nul
if errorlevel 1 (
    echo    INFO  not running. Needed only for STORAGE_TYPE=s3:
    echo          D:\Codex\s3mock-runner\start.bat
) else (
    echo    OK    S3Mock is listening
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
if /i not "%~1"=="-y" pause
