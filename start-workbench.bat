@echo off
rem ============================================================
rem  Workbench one-click launcher (reliable / idempotent)
rem  Order: Docker DB -> wait for Postgres -> backend(8080)
rem         -> frontend(5173) -> health checks -> open browser
rem  Safe to re-run: anything already listening is skipped.
rem  Every step is recorded in launch.log for diagnosis.
rem  NOTE: all health checks are raw-TCP (proxy-free). Do NOT
rem  switch them to Invoke-WebRequest: it routes through the
rem  system proxy, which can never reach localhost.
rem ============================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
set "JAR=%ROOT%server\target\workbench-server-0.1.0.jar"
set "LOG=%ROOT%launch.log"
type nul > "%LOG%"
goto :main

rem ---------- log subroutine: echo to console AND append to launch.log ----------
:log
echo %*
echo %* >> "%LOG%"
goto :eof

rem ---------- blank line to console AND launch.log ----------
:blank
echo.
echo. >> "%LOG%"
goto :eof

:main
call :log ============================================================
call :log   Workbench launcher
call :log ============================================================

rem ---------- 0) enterprise KB (optional, warn-only) ----------
set "KB_DIR=C:\Users\86131\.pi"
if not exist "%KB_DIR%\start-kb.ps1" (
  for /d %%a in ("%USERPROFILE%\Desktop\*") do (
    for /d %%b in ("%%a\*") do (
      if exist "%%b\start-kb.ps1" set "KB_DIR=%%b"
    )
  )
)
call :portopen 8765
if "%OPEN%"=="1" (
  call :log [0/4] Enterprise KB already running on :8765 - skip.
) else (
  if not exist "%KB_DIR%\start-kb.ps1" (
    call :log [0/4] Enterprise KB launcher not found - skip.
  ) else (
    call :log [0/4] Starting enterprise KB on :8765 ...
    powershell -NoProfile -ExecutionPolicy Bypass -File "%KB_DIR%\start-kb.ps1" >> "%LOG%" 2>&1
    call :log   KB launcher invoked - idempotent. If :8765 stays down,
    call :log   check %KB_DIR%\kb\api_server.err.log - workbench still works.
  )
)

rem ---------- 1) database ----------
call :log [1/4] Starting database - Docker: PostgreSQL + Redis ...
where docker >nul 2>&1
if errorlevel 1 (
  call :log   [ERROR] Docker CLI not found. Install and start Docker Desktop first.
  goto fail
)
pushd "%ROOT%server"
docker-compose up -d >> "%LOG%" 2>&1
if errorlevel 1 call :startdocker
docker-compose up -d >> "%LOG%" 2>&1
set "DOCKER_RC=%errorlevel%"
popd
if not "%DOCKER_RC%"=="0" (
  call :log   [ERROR] "docker-compose up -d" failed. Docker engine may still be down.
  call :log           Start Docker Desktop manually, wait until it says "Engine running", then re-run.
  call :log           Full details are at the end of launch.log.
  goto fail
)

rem ---------- 2) wait for Postgres ----------
call :log [2/4] Waiting for PostgreSQL on 127.0.0.1:5432 ...
set /a n=0
:waitpg
set /a n+=1
call :portopen 5432
if "%OPEN%"=="1" goto pgre
if %n% GEQ 60 (
  call :log   [WARN] PostgreSQL not ready after 60s; continuing anyway ...
  goto pgre
)
timeout /t 1 /nobreak >nul
goto waitpg
:pgre
call :log   Database is ready.

rem ---------- 3) backend ----------
call :portopen 8080
 if "%OPEN%"=="1" (
   call :log [3/4] Backend already running on :8080 - skip.
   call :log   .env changes made since this backend started are NOT picked up;
   call :log   close the "workbench-backend" window and re-run to apply them.
 ) else (
  if not exist "%JAR%" (
    call :log   [ERROR] Backend jar not found: %JAR%
    call :log           Build it first:  cd server  then  mvn -DskipTests package
    goto fail
  )
  call :log [3/4] Starting backend on :8080 ...
  rem Bridge to the enterprise KB (.pi) needs the read-only service token.
  rem Must match KB .env KB_SERVICE_TOKEN; empty => KB rejects with 401 -> proxy 500.
  rem The token is NOT stored in the repo: read it from git-ignored .kbtoken
   rem (one line, no quotes) beside this script, same pattern as .env.
  set "WORKBENCH_KB_SERVICE_TOKEN="
  if exist "%ROOT%.kbtoken" (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "(Get-Content '%ROOT%.kbtoken' -Raw).Trim().Trim([char]0xFEFF)"`) do set "WORKBENCH_KB_SERVICE_TOKEN=%%k"
    call :log   KB service token loaded from .kbtoken
  ) else (
    call :log   [WARN] No .kbtoken file - enterprise KB bridge will be rejected by the KB side.
  )
  rem ---- AI / proxy config: load .env beside this script (user-editable) ----
  rem Defaults below; .env overrides them. Put your API key in .env.
  set "WORKBENCH_AI_BASE_URL=https://apihub.agnes-ai.com/v1"
  set "WORKBENCH_AI_MODEL=agnes-2.5-flash"
  if exist "%ROOT%.env" (
    for /f "usebackq tokens=1,* delims==" %%a in ("%ROOT%.env") do (
      set "ln=%%a"
      if not "!ln:~0,1!"=="#" if not "%%b"=="" set "%%a=%%b"
    )
     set "AI_SRC=.env"
     call :log   Loaded .env
   ) else (
     set "AI_SRC=built-in defaults"
     call :log   No .env - using built-in Agnes defaults
   )
   if defined WORKBENCH_AI_API_KEY (
     call :log   AI config in effect: !WORKBENCH_AI_BASE_URL! / !WORKBENCH_AI_MODEL!
     call :log   key=!WORKBENCH_AI_API_KEY:~0,4!*** - masked, from !AI_SRC!
   ) else (
     call :log   [WARN] No API key set - put WORKBENCH_AI_API_KEY in .env,
     call :log          or save one in the writing desk "model settings".
   )
  start "workbench-backend" /D "%ROOT%server" cmd /k java -jar target\workbench-server-0.1.0.jar
)

rem ---------- 4) frontend ----------
rem Always restart the dev server: a stale :5173 process left from a previous
rem run (or an HMR-disconnected tab) keeps serving outdated JS that shows
rem "not configured" even when the backend config is perfectly fine. Kill any
rem old listener first, then start a fresh one that compiles current source.
call :log [4/4] Restarting frontend on :5173 (kill stale dev server first) ...
call :killport 5173
timeout /t 2 /nobreak >nul
start "workbench-frontend" /D "%ROOT%web" cmd /k npm run dev

rem ---------- wait backend (raw-TCP, proxy-free) ----------
call :log Waiting for backend on :8080 ...
set /a m=0
:waitbe
set /a m+=1
call :portopen 8080
if "%OPEN%"=="1" goto fe
if %m% GEQ 90 (
  call :log   [WARN] Backend still not listening on :8080. Check the "workbench-backend" window for errors.
  goto fe
)
timeout /t 1 /nobreak >nul
goto waitbe

rem ---------- wait frontend (raw-TCP, proxy-free) ----------
:fe
call :log Waiting for frontend on :5173 ...
set /a k=0
:waitfe
set /a k+=1
call :portopen 5173
if "%OPEN%"=="1" goto ready
if %k% GEQ 30 (
  call :log   [WARN] Frontend not listening on :5173 after 30s.
  call :log           Check the "workbench-frontend" window for npm errors.
  goto ready
)
timeout /t 1 /nobreak >nul
goto waitfe

:ready
call :blank
call :log ============================================================
 call :log   READY - opening http://localhost:5173 in your default browser ...
call :log   Login credentials are shown right on the login page.
call :blank
call :log   backend  : :8080  window "workbench-backend"
call :log   frontend : :5173  window "workbench-frontend"
call :log   Close those two windows to stop the services.
 call :log   The page opens in your default browser. The system proxy bypass list
 call :log   (ProxyOverride) keeps localhost/127.0.0.1 off the enterprise proxy,
 call :log   so AI chat streams are not buffered. If chat still hangs, verify
 call :log   Windows settings keep "localhost;127.0.0.1" in that bypass list.
call :log   See launch.log for the full trace.
call :log ============================================================
 rem Auto-open the page in the user's default browser - no dedicated
 rem instance: the system ProxyOverride (Windows proxy bypass list) already
 rem keeps localhost off the enterprise proxy, so SSE streams stay
 rem unbuffered and the normal browser profile/cache is reused (no lag).
call :openbrowser
pause
exit /b 0

:fail
call :blank
call :log Launch aborted. Fix the issue noted above, then re-run.
call :log See launch.log for the full trace.
pause
exit /b 1

rem ---------- helper: launch Docker Desktop and wait (max 120s) for the engine ----------
:startdocker
call :log   [WARN] Docker engine not reachable - launching Docker Desktop and waiting ...
set "DD_EXE=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DD_EXE%" (
  call :log   [ERROR] Docker Desktop not found at: %DD_EXE%
  goto :eof
)
start "" "%DD_EXE%"
set /a dw=0
:dwloop
timeout /t 3 /nobreak >nul
docker info >nul 2>&1
if not errorlevel 1 (
  call :log   Docker engine is up. Retrying docker-compose ...
  goto :eof
)
set /a dw+=3
if !dw! LSS 120 goto dwloop
call :log   [ERROR] Docker engine still not running after 120s.
goto :eof

rem ---------- helper: is TCP port open on 127.0.0.1? sets OPEN=1/0 ----------
:portopen
set "OPEN=0"
for /f "tokens=2,4" %%a in ('netstat -ano -p tcp ^| findstr /I "LISTENING"') do (
  if "%%a"=="127.0.0.1:%1" set "OPEN=1"
  if "%%a"=="0.0.0.0:%1" set "OPEN=1"
  if "%%a"=="[::]:%1" set "OPEN=1"
  if "%%a"=="[::1]:%1" set "OPEN=1"
)
exit /b 0

rem ---------- helper: kill the process listening on a local TCP port ----------
:killport
for /f "tokens=5" %%a in ('netstat -ano -p tcp ^| findstr /I "LISTENING" ^| findstr ":%1"') do (
  call :log   Killing stale process on :%1 (PID %%a)
  taskkill /F /PID %%a >nul 2>&1
)
exit /b 0

 rem ---------- helper: open the app in the default browser (proxy-safe) ----------
 :openbrowser
 rem Read-only check: does the Windows proxy bypass list cover 127.0.0.1?
 rem If not, an enterprise proxy would buffer SSE streams and the AI chat
 rem would appear to hang ("thinking..." forever).
 set "PO="
 for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyOverride 2^>nul') do set "PO=%%b"
 set "PO_HIT=0"
 set "PROXY_ON=0"
 for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable 2^>nul') do set "PROXY_ON=%%b"
 if defined PO (
   echo "!PO!" | findstr /C:"127.0.0.1" >nul 2>&1 && set "PO_HIT=1"
 )
 rem Only warn when a system proxy is actually on; no proxy = nothing to bypass.
 if "!PROXY_ON!"=="1" if "!PO_HIT!"=="0" (
   call :log   [WARN] 127.0.0.1 is not in the Windows proxy bypass list
   call :log   - ProxyOverride. If the AI chat hangs, add "localhost;127.0.0.1"
   call :log          to the bypass list:  Windows Settings, Network and internet,
 )
 call :log   Opening http://localhost:5173 in your default browser ...
 start "" "http://localhost:5173"
 goto :eof
