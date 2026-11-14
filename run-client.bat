@echo off
rem 双击启动开发客户端。逻辑全在 run-client.ps1 里，这里只负责调用。
rem 用途：不想让 AI 侧留后台任务时，自己双击这个文件即可（关掉游戏窗口后本窗口自动收尾）。
setlocal
set "PSEXE=pwsh"
where pwsh >nul 2>nul
if errorlevel 1 set "PSEXE=powershell"
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-client.ps1" %*
echo.
echo [run-client] 游戏已退出。
pause
