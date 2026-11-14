@echo off
setlocal
cd /d "%~dp0"
set "PSEXE=pwsh"
where pwsh >nul 2>nul
if errorlevel 1 set "PSEXE=powershell"
echo [run-client] launcher: %PSEXE%
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-client.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
echo [run-client] exited with code %RC%
pause
