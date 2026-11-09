# Sync every external mod jar into the dev run directories:
#   - HuanJ\libs                (prerequisite mods: Sophisticated Backpacks / Core / AnvilLib ...)
#   - sibling folders of HuanJ that contain .jar files  (e.g. the "add these mods" folder)
# Targets: project\run\mods  and  project\run-server\mods
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$parent = Split-Path $root -Parent

$srcDirs = @()
$libs = Join-Path $root 'libs'
if (Test-Path $libs) { $srcDirs += $libs }
Get-ChildItem -LiteralPath $parent -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.FullName -eq $root) { return }
    $jars = Get-ChildItem -LiteralPath $_.FullName -Filter *.jar -File -ErrorAction SilentlyContinue
    if ($jars) { $srcDirs += $_.FullName }
}

$destDirs = @(
    (Join-Path $root 'project\run\mods'),
    (Join-Path $root 'project\run-server\mods')
)

foreach ($d in $destDirs) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$count = 0
foreach ($src in $srcDirs) {
    foreach ($jar in (Get-ChildItem -LiteralPath $src -Filter *.jar -File)) {
        foreach ($d in $destDirs) {
            Copy-Item -LiteralPath $jar.FullName -Destination $d -Force
            $count++
        }
    }
    Write-Host ("[sync] " + $src)
}
Write-Host ("[sync] copied " + $count + " file(s) into run\mods and run-server\mods")
