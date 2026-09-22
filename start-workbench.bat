@echo off
rem ============================================================
rem  Workbench one-click launcher (reliable / idempotent)
rem  Order: Docker DB -> wait for Postgres -> backend(8080)
rem         -> frontend(5173) -> wait for backend health -> ready
rem  Safe to re-run: anything already listening is skipped.
rem ============================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "JAR=%ROOT%server\target\workbench-server-0.1.0.jar"

echo ============================================================
echo   Workbench launcher
echo ============================================================

rem ---------- 1) database ----------
echo [1/4] Starting database (Docker: PostgreSQL + Redis) ...
where docker >nul 2>&1
if errorlevel 1 (
  echo   [ERROR] Docker CLI not found. Install and start Docker Desktop first.
  goto fail
)
pushd "%ROOT%server"
docker-compose up -d
set "DOCKER_RC=%errorlevel%"
popd
if not "%DOCKER_RC%"=="0" (
  echo   [ERROR] "docker-compose up -d" failed. Is Docker Desktop running?
  echo           Start Docker Desktop, wait until it says "Engine running", then re-run this.
  goto fail
)

rem ---------- 2) wait for Postgres ----------
echo [2/4] Waiting for PostgreSQL on 127.0.0.1:5432 ...
set /a n=0
:waitpg
set /a n+=1
call :portopen 5432
if "%OPEN%"=="1" goto pgre
if %n% GEQ 60 (
  echo   [WARN] PostgreSQL not ready after 60s; continuing anyway ...
  goto pgre
)
timeout /t 1 /nobreak >nul
goto waitpg
:pgre
echo   Database is ready.

rem ---------- 3) backend ----------
call :portopen 8080
if "%OPEN%"=="1" (
  echo [3/4] Backend already running on :8080 - skip.
) else (
  if not exist "%JAR%" (
    echo   [ERROR] Backend jar not found:
    echo           %JAR%
    echo           Build it first:  cd server  ^&^&  mvn -DskipTests package
    goto fail
  )
  echo [3/4] Starting backend on :8080 ...
  start "workbench-backend" cmd /k "cd /d %ROOT%server && java -jar target\workbench-server-0.1.0.jar"
)

rem ---------- 4) frontend ----------
call :portopen 5173
if "%OPEN%"=="1" (
  echo [4/4] Frontend already running on :5173 - skip.
) else (
  echo [4/4] Starting frontend on :5173 ...
  start "workbench-frontend" cmd /k "cd /d %ROOT%web && npm run dev"
)

rem ---------- wait backend health ----------
echo Waiting for backend health (/actuator/health) ...
set /a m=0
:waitbe
set /a m+=1
powershell -NoProfile -Command "try{Invoke-WebRequest -UseBasicParsing -Uri http://localhost:8080/actuator/health -TimeoutSec 2 ^| Out-Null; exit 0}catch{exit 1}" >nul 2>&1
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
echo   READY  -  open in browser:  http://localhost:5173
echo   (login credentials are shown right on the login page)
echo.
echo   backend  : :8080  window "workbench-backend"
echo   frontend : :5173  window "workbench-frontend"
echo   Close those two windows to stop the services.
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
