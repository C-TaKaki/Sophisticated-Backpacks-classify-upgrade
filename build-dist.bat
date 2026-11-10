@echo off
chcp 65001 >nul
setlocal
rem ============================================================
rem  编译 sbaddon，产物输出到 HuanJ\dist
rem  用法：双击本文件，或在命令行 build-dist.bat / build-dist.bat clean build
rem ============================================================

set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%tools\jdk21"
set "GRADLE_USER_HOME=%ROOT%gradle-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [build-dist] 找不到 JDK：%JAVA_HOME%
    goto :fail
)

pushd "%ROOT%project"
if "%~1"=="" (
    rem 不带参数就只跑 build；build 完成后 collectJar 会自动把 jar 复制到 dist
    call gradlew.bat build --console=plain
) else (
    call gradlew.bat %*
)
set "CODE=%ERRORLEVEL%"
popd

if not "%CODE%"=="0" (
    echo.
    echo [build-dist] 编译失败，退出码 %CODE%
    if "%~1"=="" pause
    exit /b %CODE%
)

rem 兜底：万一 collectJar 没跑，直接把 build\libs 里的 jar 拷过去
if not exist "%ROOT%dist" mkdir "%ROOT%dist"
if not exist "%ROOT%dist\*.jar" (
    copy /y "%ROOT%project\build\libs\*.jar" "%ROOT%dist\" >nul
)

echo.
echo [build-dist] 完成，产物：
dir /b "%ROOT%dist\*.jar"
echo [build-dist] 目录：%ROOT%dist
if "%~1"=="" pause
exit /b 0

:fail
if "%~1"=="" pause
exit /b 1
