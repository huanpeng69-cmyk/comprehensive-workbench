@echo off
rem ============================================================
rem  Workbench production launcher (department LAN deployment)
rem  Order: Docker DB -> backend(8080) -> build check for
rem         web/dist -> nginx(:80) -> health check
rem  Safe to re-run: anything already running is skipped.
rem ============================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "JAR=%ROOT%server\target\workbench-server-0.1.0.jar"
set "DIST=%ROOT%web\dist\index.html"

echo ============================================================
echo   Workbench PROD launcher  (entry: http://<server-ip>/)
echo ============================================================

rem ---------- 0) frontend build must exist ----------
if not exist "%DIST%" (
  echo   [ERROR] web\dist not found. Build first:
  echo           cd web ^&^& npm run build
  goto fail
)

rem ---------- 1) docker engine + database ----------
where docker >nul 2>&1
if errorlevel 1 (
  echo   [ERROR] Docker CLI not found. Start Docker Desktop first.
  goto fail
)
docker info >nul 2>&1
if errorlevel 1 (
  echo   [ERROR] Docker engine not ready. Start Docker Desktop, wait until "Engine running", re-run.
  goto fail
)
pushd "%ROOT%server"
docker-compose up -d
set "DOCKER_RC=%errorlevel%"
popd
if not "%DOCKER_RC%"=="0" (
  echo   [ERROR] "docker compose up -d" failed. Check server\.env is configured.
  goto fail
)

rem ---------- 2) wait for Postgres ----------
echo [1/3] Waiting for PostgreSQL on 127.0.0.1:5432 ...
set /a n=0
:waitpg
set /a n+=1
call :portopen 5432
if "%OPEN%"=="1" goto pgre
if %n% GEQ 60 (
  echo   [ERROR] PostgreSQL not ready after 60s. Check "docker compose logs" in server\.
  goto fail
)
timeout /t 1 /nobreak >nul
goto waitpg
:pgre
echo   Database is ready.

rem ---------- 3) backend ----------
call :portopen 8080
if "%OPEN%"=="1" (
  echo [2/3] Backend already running on :8080 - skip.
) else (
  echo [2/3] Starting backend on :8080 ...
  start "workbench-backend" cmd /k "cd /d %ROOT%server && java -jar target\workbench-server-0.1.0.jar"
)

rem ---------- 4) nginx entry :80 ----------
echo [3/3] Starting Nginx entry on :80 ...
pushd "%ROOT%deploy"
docker-compose -f docker-compose.prod.yml up -d
set "NGINX_RC=%errorlevel%"
popd
if not "%NGINX_RC%"=="0" (
  echo   [ERROR] nginx failed to start. Is port 80 already in use? (netstat -ano ^| findstr :80)
  goto fail
)

rem ---------- wait backend health ----------
echo Waiting for backend health via http://localhost/api/actuator/health ...
set /a m=0
:waitbe
set /a m+=1
powershell -NoProfile -Command "try{Invoke-WebRequest -UseBasicParsing -Uri http://localhost/actuator/health -TimeoutSec 2 ^| Out-Null; exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto ready
if %m% GEQ 90 (
  echo   [WARN] Backend still not healthy. Check the "workbench-backend" window for errors.
  goto ready
)
timeout /t 1 /nobreak >nul
goto waitbe

:ready
echo.
echo ============================================================
echo   READY  -  LAN entry:  http://<this-server-ip>/
echo   (local check: http://localhost/ )
echo.
echo   nginx    : :80    container "workbench-nginx"
echo   backend  : :8080  window "workbench-backend"
echo   database : :5432  127.0.0.1 only  (not LAN-exposed)
echo   redis    : :6379  127.0.0.1 only  (not LAN-exposed)
echo   Stop     : close "workbench-backend" window, then
echo              cd deploy ^&^& docker compose -f docker-compose.prod.yml down
echo ============================================================
pause
exit /b 0

:fail
echo.
echo Launch aborted. Fix the issue noted above, then re-run.
pause
exit /b 1

rem ---------- helper: is TCP port open on 127.0.0.1? sets OPEN=1/0 ----------
:portopen
set "OPEN=0"
powershell -NoProfile -Command "try{(New-Object Net.Sockets.TcpClient).Connect('127.0.0.1',%1);exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 set "OPEN=1"
exit /b 0
