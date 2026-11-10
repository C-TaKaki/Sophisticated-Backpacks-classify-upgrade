# Sync external mods, then launch the NeoForge dev client (game dir: project\run).
# 关闭游戏窗口后会把本局启动的 JVM / Gradle 守护进程一并清理干净，脚本返回时不留后台进程。
# 用法：run-client.ps1 -QuickPlay "新的世界"  → 跳过主菜单直接进该存档；不带参数则正常启动
param([string]$QuickPlay)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'sync-mods.ps1')

# build.gradle 会读这个文件拼 --quickPlaySingleplayer（走文件是为了避开 gradlew.bat 拆参数）
$quickPlayFile = Join-Path $root 'project\quickplay.txt'
if ($QuickPlay) {
    # 无 BOM 的 UTF-8，否则 Groovy 读出来会多一个 \uFEFF 前导字符
    [System.IO.File]::WriteAllText($quickPlayFile, $QuickPlay, [System.Text.UTF8Encoding]::new($false))
    Write-Host "[run-client] quickPlay -> $QuickPlay"
} elseif (Test-Path -LiteralPath $quickPlayFile) {
    Remove-Item -LiteralPath $quickPlayFile -Force
}

$env:JAVA_HOME = Join-Path $root 'tools\jdk21'
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$runDir = Join-Path $root 'project\run'

# 只匹配「开发客户端」进程：运行目录是 project\run，或命令行里带 NeoForge 开发启动器 / 客户端主类。
# 这样不会误杀本机其它 java 程序（服务端、IDE、其它 Gradle 构建）。
function Get-DevClientJava {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $cmd = $_.CommandLine
            $cmd -and (
                $cmd -like "*$runDir*" -or
                $cmd -like "*net.neoforged.devlaunch*" -or
                $cmd -like "*net.minecraft.client.main.Main*"
            )
        }
}

# 启动前已经存在的进程（例如另一个会话里的客户端）不动
$preexisting = @{}
Get-DevClientJava | ForEach-Object { $preexisting[$_.ProcessId] = $true }

Push-Location (Join-Path $root 'project')
try {
    & .\gradlew.bat runClient --console=plain @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

# 1) gradlew 已经返回，但游戏 JVM 可能还在收尾（写存档 / 释放资源），给它 10 秒自然退出
$deadline = (Get-Date).AddSeconds(10)
$left = @(Get-DevClientJava | Where-Object { -not $preexisting.ContainsKey($_.ProcessId) })
while ($left.Count -gt 0 -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $left = @(Get-DevClientJava | Where-Object { -not $preexisting.ContainsKey($_.ProcessId) })
}

# 2) 超时仍未退出的强制结束
foreach ($p in $left) {
    Write-Host "[run-client] 结束残留游戏进程 PID=$($p.ProcessId)"
    try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch { }
}

# 3) 停掉 Gradle 守护进程：它是常驻 java 进程，不关掉同样算后台残留
Push-Location (Join-Path $root 'project')
try {
    & .\gradlew.bat --stop 2>&1 | Out-Null
} catch {
    # gradle 向 stderr 写日志会触发 NativeCommandError，忽略即可，清理逻辑不受影响
} finally {
    Pop-Location
}

# 4) 兜底确认：还有残留就再杀一次，保证脚本返回时干净
$remain = @(Get-DevClientJava | Where-Object { -not $preexisting.ContainsKey($_.ProcessId) })
foreach ($p in $remain) {
    try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch { }
}

exit $code
