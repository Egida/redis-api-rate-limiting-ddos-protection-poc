@echo off
set "ROOT=%~dp0..\..\"
pushd "%ROOT%redis-rate-limit-poc"

rem Never stop an arbitrary process; refuse if port 8080 is occupied.
netstat -aon | findstr /R /C:":8080 .*LISTENING" >nul
if not errorlevel 1 (
    echo Port 8080 is already occupied. Stop the intended process yourself, then retry.
    popd
    exit /b 1
)

if not defined RATELIMIT_ADMIN_USER (
    echo Set RATELIMIT_ADMIN_USER in this shell before starting the backend.
    popd
    exit /b 1
)
if not defined RATELIMIT_ADMIN_PASSWORD (
    echo Set RATELIMIT_ADMIN_PASSWORD in this shell before starting the backend.
    popd
    exit /b 1
)

set "JAR="
for %%f in (target\redis-rate-limit-poc-*.jar) do if "%%~xf"==".jar" set "JAR=%%f"

start "RateGuard API :8080" cmd /c "java -jar %JAR% > logs\backend.log 2>&1"

popd
