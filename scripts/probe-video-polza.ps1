$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$secure = Read-Host 'Polza.AI key (input hidden)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:POLZA_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try { & node "$projectRoot/scripts/probe-video-polza.mjs" @args }
finally { Remove-Item Env:POLZA_KEY -ErrorAction SilentlyContinue }
