# Sync external mods, then launch the NeoForge dev client (game dir: project\run)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'sync-mods.ps1')

$env:JAVA_HOME = Join-Path $root 'tools\jdk21'
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Push-Location (Join-Path $root 'project')
try {
    & .\gradlew.bat runClient --console=plain @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
