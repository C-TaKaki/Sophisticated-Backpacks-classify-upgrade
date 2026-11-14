# Build the mod jar. Output: project/build/libs/*.jar and HuanJ/dist/*.jar
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$env:JAVA_HOME = Join-Path $root 'tools\jdk21'
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-home'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Push-Location (Join-Path $root 'project')
try {
    & .\gradlew.bat @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
