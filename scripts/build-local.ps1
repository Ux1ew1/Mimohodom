param([string[]]$Tasks = @('assembleDebug', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$env:ANDROID_HOME = "$projectRoot/.tools/android"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
Push-Location $projectRoot
try {
    & "$projectRoot/.tools/gradle/gradle-8.11.1/bin/gradle.bat" @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
