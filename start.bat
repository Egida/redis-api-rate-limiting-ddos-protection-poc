@echo off
rem Delayed expansion is required: the polling loops below set *_OK inside a
rem for-loop and test it in the same block, which %VAR% cannot see (the block is
rem parsed once, before the loop runs). !VAR! is re-read each iteration.
setlocal EnableDelayedExpansion

rem ---------------------------------------------------------------------------
rem RateGuard - start the Redis rate limiting POC (Redis + Spring Boot + Angular)
rem
rem Run from anywhere:  start.bat
rem Stop everything:   see the ports listed at the end.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "REDIS_NAME=ratelimit-redis"
set "REDIS_PORT=6379"
set "APP_PORT=8080"
set "UI_PORT=4200"

echo.
echo  ==========================================================
echo   RateGuard - Redis API Rate Limiting and DDoS POC
echo  ==========================================================
echo.

rem --- 1. toolchain ---------------------------------------------------------
echo [1/5] Checking the toolchain...

where java >nul 2>&1 || (echo   ERROR: java not found. Install JDK 21 and add it to PATH. & goto :fail)
where mvn  >nul 2>&1 || (echo   ERROR: mvn not found. Install Maven and add it to PATH.  & goto :fail)
where node >nul 2>&1 || (echo   ERROR: node not found. Install Node 20+ and add it to PATH.& goto :fail)
where npm  >nul 2>&1 || (echo   ERROR: npm not found.                                       & goto :fail)
where docker >nul 2>&1 || (echo   ERROR: docker not found. Docker Desktop must be running.  & goto :fail)
echo   java / maven / node / npm / docker all present.

docker info >nul 2>&1 || (echo   ERROR: Docker is installed but not responding. Start Docker Desktop first. & goto :fail)
echo.

rem --- 2. Redis -------------------------------------------------------------
echo [2/5] Redis...

rem Ask docker directly instead of grepping `docker ps`. `findstr /x` does not
rem match here: docker writes LF-only line endings, and findstr's whole-line
rem anchor silently fails on them (substring matching still works, which is why
rem this looks like it works until the container is already there).
rem   docker inspect fails -> no such container -> create it
rem   State.Running=true   -> leave it alone
rem   State.Running=false  -> start the existing container
docker inspect -f "{{.State.Running}}" %REDIS_NAME% > "%TEMP%\ratelimit-redis-state.txt" 2>nul
if errorlevel 1 (
  echo   creating container %REDIS_NAME%...
  docker run -d --name %REDIS_NAME% -p %REDIS_PORT%:6379 redis:7-alpine >nul
  if errorlevel 1 (
    echo   ERROR: could not create the Redis container.
    echo   If you have an old container under this name, run: docker rm -f %REDIS_NAME%
    goto :fail
  )
) else (
  set /p REDIS_RUNNING=<"%TEMP%\ratelimit-redis-state.txt"
  if "!REDIS_RUNNING!"=="true" (
    echo   container already running.
  ) else (
    echo   container exists but is stopped, starting it...
    docker start %REDIS_NAME% >nul
    if errorlevel 1 ( echo   ERROR: could not start the existing container. & goto :fail )
  )
)

rem Wait for Redis to accept commands rather than assuming. Test the exit code of
rem `docker exec` itself - it is non-zero when the container is gone or the
rem server has not finished booting. (Do not pipe through `findstr /x` here: the
rem LF-only output defeats its whole-line anchor, so it reports failure even
rem when Redis is healthy.)
set "REDIS_OK=0"
for /l %%i in (1,1,30) do (
  if "!REDIS_OK!"=="0" (
    docker exec %REDIS_NAME% redis-cli PING >nul 2>&1
    if not errorlevel 1 ( set "REDIS_OK=1" ) else ( timeout /t 1 /nobreak >nul )
  )
)
if "!REDIS_OK!"=="0" ( echo   ERROR: Redis did not answer PING. Check: docker logs %REDIS_NAME% & goto :fail )
echo   Redis is up on %REDIS_PORT%.
echo.

rem --- 3. backend jar -------------------------------------------------------
rem Note: every path below is relative to the backend folder, so a folder name
rem containing spaces can never split a command argument.
echo [3/5] Backend...

pushd "%ROOT%redis-rate-limit-poc"
if not exist "logs" mkdir "logs" >nul 2>&1

set "JAR="
for %%f in (target\redis-rate-limit-poc-*.jar) do if "%%~xf"==".jar" set "JAR=%%f"

if defined JAR (
  echo   using existing jar %JAR%
) else (
  echo   no jar found, building ^(tests skipped - use scripts\verify-all.ps1 for the full gate^)...
  call mvn -B package -DskipTests
  if errorlevel 1 ( echo   ERROR: Maven build failed. See output above. & popd & goto :fail )
  for %%f in (target\redis-rate-limit-poc-*.jar) do if "%%~xf"==".jar" set "JAR=%%f"
  if not defined JAR ( echo   ERROR: build finished but no jar was produced. & popd & goto :fail )
)
popd
echo.

rem --- 4. backend service ---------------------------------------------------
echo [4/5] Starting the Spring Boot API...

rem Already up? Do not start a second one.
powershell -NoProfile -Command ^
  "try { if ((Invoke-RestMethod 'http://localhost:%APP_PORT%/actuator/health' -TimeoutSec 3).status -eq 'UP') { exit 0 } } catch {}; exit 1" >nul 2>&1
if not errorlevel 1 (
  echo   API already healthy on %APP_PORT%, not starting another.
) else (
  rem Launch via `start` so the jar runs in its own titled window and this script
  rem keeps polling. /D sets the working directory and %JAR% is relative, so a
  rem root folder containing spaces can never split an argument - exactly what
  rem broke the earlier Start-Process attempt ("Unable to access jarfile ...\Redis").
  start "RateGuard API :%APP_PORT%" /D "%ROOT%redis-rate-limit-poc" cmd /c java -jar %JAR% ^> logs\backend.log 2^>^&1
  echo   waiting for /actuator/health to report UP...
  set "APP_OK=0"
  for /l %%i in (1,1,60) do (
    if "!APP_OK!"=="0" (
      powershell -NoProfile -Command ^
        "try { if ((Invoke-RestMethod 'http://localhost:%APP_PORT%/actuator/health' -TimeoutSec 2).status -eq 'UP') { exit 0 } } catch {}; exit 1" >nul 2>&1
      if not errorlevel 1 ( set "APP_OK=1" ) else ( timeout /t 1 /nobreak >nul )
    )
  )
  if "!APP_OK!"=="0" (
    echo   ERROR: the API did not become healthy within 60s.
    echo   Last lines of redis-rate-limit-poc\logs\backend.log:
    powershell -NoProfile -Command "Get-Content '%ROOT%redis-rate-limit-poc\logs\backend.log' -Tail 20"
    goto :fail
  )
)
echo   API is UP on %APP_PORT%.
echo.

rem --- 5. Angular console ---------------------------------------------------
echo [5/5] Angular console...

pushd "%ROOT%frontend"
if not exist "logs" mkdir "logs" >nul 2>&1
if not exist "node_modules" (
  echo   installing npm dependencies ^(first run only^)...
  call npm install
  if errorlevel 1 ( echo   ERROR: npm install failed. & popd & goto :fail )
)
popd

rem Already serving? Reuse it.
powershell -NoProfile -Command ^
  "try { if ((Invoke-WebRequest 'http://localhost:%UI_PORT%/' -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { exit 0 } } catch {}; exit 1" >nul 2>&1
if not errorlevel 1 (
  echo   console already serving on %UI_PORT%, not starting another.
) else (
  rem `npm start` rather than calling ng.js directly: npm resolves the right
  rem local CLI version, so this keeps working after a dependency bump.
  start "RateGuard Console :%UI_PORT%" /D "%ROOT%frontend" cmd /c npm start ^> logs\console.log 2^>^&1
  echo   waiting for the dev server...
  set "UI_OK=0"
  for /l %%i in (1,1,90) do (
    if "!UI_OK!"=="0" (
      powershell -NoProfile -Command ^
        "try { if ((Invoke-WebRequest 'http://localhost:%UI_PORT%/' -TimeoutSec 2 -UseBasicParsing).StatusCode -eq 200) { exit 0 } } catch {}; exit 1" >nul 2>&1
      if not errorlevel 1 ( set "UI_OK=1" ) else ( timeout /t 1 /nobreak >nul )
    )
  )
  if "!UI_OK!"=="0" (
    echo   WARNING: the console did not answer on %UI_PORT% within 90s.
    echo   Last lines of frontend\logs\console.log:
    if exist "%ROOT%frontend\logs\console.log" powershell -NoProfile -Command "Get-Content '%ROOT%frontend\logs\console.log' -Tail 20"
    echo   The API is still running on %APP_PORT%, so you can still test with curl.
    goto :done
  )
)
echo   console is serving on %UI_PORT%.
echo.

:done
echo  ==========================================================
echo   Running
echo  ==========================================================
echo    Console  http://localhost:%UI_PORT%/
echo    API      http://localhost:%APP_PORT%/
echo    Health   http://localhost:%APP_PORT%/actuator/health
echo.
echo    Try it: open the console, set Requests to 150, press Start demo.
echo    Expect 100 allowed and 50 rejected, first 429 at request #101.
echo.
echo    Stopping:
echo      backend   close the "RateGuard API" window, or
echo                taskkill /FI "WINDOWTITLE eq RateGuard API :%APP_PORT%*" /T /F
echo      console   close the "RateGuard Console" window
echo      redis     docker stop %REDIS_NAME%
echo.
echo    Logs:
echo      redis-rate-limit-poc\logs\backend.log
echo      frontend\logs\console.log
echo.
echo  ==========================================================
echo.
start "" http://localhost:%UI_PORT%/
exit /b 0

:fail
echo.
echo  ==========================================================
echo   STARTUP FAILED - see the message above.
echo  ==========================================================
echo.
pause
exit /b 1
