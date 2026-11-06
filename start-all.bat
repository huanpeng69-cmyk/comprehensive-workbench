@echo off
rem ============================================================
rem  One-click launcher: Enterprise KB (.pi :8765) + Workbench
rem  Order: KB first (the workbench KB panel depends on it),
rem         wait for :8765, then the workbench
rem         (Docker DB / backend :8081 / frontend :5173).
rem  Both launchers are idempotent: already-running = skip.
rem  Every step is recorded in start-all.log for diagnosis.
rem
rem  NOTE 1: keep this file ASCII-only and CRLF. cmd.exe parses
rem  bat files in the ANSI codepage (GBK on zh-CN Windows);
rem  UTF-8 Chinese text here turns into mojibake and breaks
rem  paths/labels (seen as "is not recognized as a command").
rem  NOTE 2: we call start-kb.ps1 directly (pure-ASCII path)
rem  instead of the Chinese-named wrapper bat.
rem ============================================================
setlocal enabledelayedexpansion
set "WB_DIR=%~dp0"
set "KB_DIR=C:\Users\86131\.pi"
if not exist "%KB_DIR%\start-kb.ps1" (
  for /d %%a in ("%USERPROFILE%\Desktop\*") do (
    for /d %%b in ("%%a\*") do (
      if exist "%%b\start-kb.ps1" set "KB_DIR=%%b"
    )
  )
)
set "LOG=%WB_DIR%start-all.log"
type nul > "%LOG%"
goto :main

rem ---------- log subroutine: echo to console AND append to start-all.log ----------
:log
echo %*
echo %* >> "%LOG%"
goto :eof

rem ---------- blank line to console AND start-all.log ----------
:blank
echo.
echo. >> "%LOG%"
goto :eof

:main
call :log ============================================================
call :log   One-click launcher: Enterprise KB + Workbench
call :log ============================================================

rem ---------- 1) enterprise knowledge base (.pi, :8765) ----------
call :log [1/2] Starting enterprise KB in .pi on :8765 ...
if not exist "%KB_DIR%\start-kb.ps1" (
  call :log   [ERROR] Enterprise KB launcher not found: %KB_DIR%
  goto fail
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%KB_DIR%\start-kb.ps1"
if errorlevel 1 (
  call :log   [ERROR] KB launcher failed. Check .pi\kb\api_server.err.log
  goto fail
)
call :log   KB launcher returned; already-running is skipped inside.

rem ---------- wait for KB port 8765 (raw-TCP, proxy-free) ----------
call :log Waiting for enterprise KB on :8765 ...
set /a n=0
:waitkb
set /a n+=1
call :portopen 8765
if "%OPEN%"=="1" goto kbre
if %n% GEQ 30 (
  call :log   [WARN] :8765 not ready after 30s. KB Q&A will be unavailable,
  call :log          but the rest of the workbench still works.
  call :log          Check .pi\kb\api_server.err.log for details.
  goto wb
)
timeout /t 1 /nobreak >nul
goto waitkb
:kbre
call :log   Enterprise KB is ready on :8765.

rem ---------- 2) workbench ----------
:wb
call :blank
call :log [2/2] Starting workbench - Docker DB / backend :8081 / frontend :5173 ...
start "Workbench launcher" /D "%WB_DIR%" cmd /k "start-workbench.bat"
call :blank
call :log All done. Close the KB / backend / frontend windows to stop services.
call :log See %LOG% for the full trace.
rem  keep the console open: a double-click runs this with cmd /c,
rem  which closes the window instantly without a pause (flashes back).
pause
goto :eof

:fail
call :blank
call :log Launch aborted. Fix the issue noted above, then re-run.
call :log See %LOG% for the full trace.
pause
exit /b 1

rem ---------- helper: is TCP port open on 127.0.0.1? sets OPEN=1/0 ----------
:portopen
set "OPEN=0"
powershell -NoProfile -Command "try{(New-Object Net.Sockets.TcpClient).Connect('127.0.0.1',%1);exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 set "OPEN=1"
exit /b 0
