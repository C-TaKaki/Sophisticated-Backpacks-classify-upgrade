# Sync external mods, then launch the NeoForge dev client (game dir: project\run).
# 关闭游戏窗口后会把本局启动的 JVM / Gradle 守护进程一并清理干净，脚本返回时不留后台进程。
# 用法：run-client.ps1 [-QuickPlay "存档名"] [-NoQuickPlay]
#   -QuickPlay   指定存档；不给则自动进「最近修改过的存档」
#   -NoQuickPlay 停在主菜单
# 窗口出现后自动最大化（见脚本里的 Win32 监视任务）
param([string]$QuickPlay, [switch]$NoQuickPlay)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'sync-mods.ps1')

# 默认进最近修改的存档：省掉每次手点「单人游戏 -> 进入世界」
if (-not $QuickPlay -and -not $NoQuickPlay) {
    $savesDir = Join-Path $root 'project\run\saves'
    if (Test-Path -LiteralPath $savesDir) {
        $latest = Get-ChildItem -LiteralPath $savesDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) { $QuickPlay = $latest.Name }
    }
}

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

# 窗口最大化：Gradle 的 runClient 会阻塞到游戏关闭，所以在同一会话里起一个后台 Job 轮询窗口标题，
# 找到开发客户端窗口就调 Win32 ShowWindow(SW_MAXIMIZE) 并置前。游戏退出后这个 Job 会被清掉。
$maximizeJob = $null
try {
    $maximizeJob = Start-Job -ScriptBlock {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class SbaddonWin {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
}
"@
        $state = @{ Hwnd = [IntPtr]::Zero }
        $deadline = (Get-Date).AddMinutes(10)
        while ((Get-Date) -lt $deadline) {
            $state.Hwnd = [IntPtr]::Zero
            $callback = [SbaddonWin+EnumWindowsProc] {
                param($hWnd, $lParam)
                if ([SbaddonWin]::IsWindowVisible($hWnd)) {
                    $sb = New-Object System.Text.StringBuilder 512
                    [void][SbaddonWin]::GetWindowText($hWnd, $sb, 512)
                    # 开发客户端标题形如 "Minecraft* 1.21.1"
                    if ($sb.ToString() -like 'Minecraft*') {
                        $state.Hwnd = $hWnd
                        return $false
                    }
                }
                return $true
            }
            [void][SbaddonWin]::EnumWindows($callback, [IntPtr]::Zero)
            if ($state.Hwnd -ne [IntPtr]::Zero) {
                [void][SbaddonWin]::ShowWindow($state.Hwnd, 3)   # SW_MAXIMIZE
                [void][SbaddonWin]::SetForegroundWindow($state.Hwnd)
                break
            }
            Start-Sleep -Milliseconds 500
        }
    }
} catch {
    Write-Host "[run-client] 窗口最大化任务没起来（不影响游戏）：$($_.Exception.Message)"
}

Push-Location (Join-Path $root 'project')
try {
    & .\gradlew.bat runClient --console=plain @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

# 游戏已经关了，最大化监视任务没用了
if ($maximizeJob) {
    Stop-Job $maximizeJob -ErrorAction SilentlyContinue
    Remove-Job $maximizeJob -Force -ErrorAction SilentlyContinue
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
