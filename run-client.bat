@echo off
rem Launch dev client with PowerShell 7 only. No 5.1 fallback.
setlocal
cd /d "%~dp0"
set "PSEXE=C:\Users\takak\AppData\Local\Microsoft\WindowsApps\pwsh.exe"
if not exist "%PSEXE%" (
    echo [run-client] PowerShell 7 not found at "%PSEXE%"
    echo [run-client] install pwsh 7 first, this launcher will not fall back to 5.1
    pause
    exit /b 1
)
echo [run-client] launcher: pwsh 7
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-client.ps1" %*
set "RC=%ERRORLEVEL%"
echo.
echo [run-client] exited with code %RC%
pause
