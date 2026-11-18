$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$json = "$projectRoot/.tools/json.jar"
if (-not (Test-Path $json)) { throw 'Missing .tools/json.jar' }
$out = "$projectRoot/.tools/sync-tests"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
$files = @(
  "$src/SyncCrypto.java", "$src/SyncRecord.java", "$src/SyncPairing.java", "$src/SyncStore.java",
  "$src/MiniHttp.java", "$src/SyncClient.java",
  "$projectRoot/tests/SyncTest.java", "$projectRoot/tests/SyncE2ETest.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $json -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Sync test compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$json" "app.fastnotes.SyncTest"
if ($LASTEXITCODE -ne 0) { throw 'SyncTest failed' }
& node "$projectRoot/tests/sync-sql.cjs"
if ($LASTEXITCODE -ne 0) { throw 'Sync SQL tests failed' }

# Сквозная проверка с Rust-сервером: нужен cargo. Без него шаг пропускается.
$cargo = Get-Command cargo -ErrorAction SilentlyContinue
if (-not $cargo -and (Test-Path "$HOME/.cargo/bin/cargo.exe")) { $env:PATH = "$HOME/.cargo/bin;$env:PATH"; $cargo = Get-Command cargo }
if ($cargo) {
  Push-Location "$projectRoot/desktop"
  try {
    & cargo build -q -p mimo-core --example headless
    if ($LASTEXITCODE -ne 0) { throw 'Rust headless build failed' }
  } finally { Pop-Location }
  $exe = "$projectRoot/desktop/target/debug/examples/headless.exe"
  if (-not (Test-Path $exe)) { $exe = "$projectRoot/desktop/target/debug/examples/headless" }
  & node "$projectRoot/tests/sync-e2e.cjs" $exe "$javaRoot/bin/java.exe" "$out;$json"
  if ($LASTEXITCODE -ne 0) { throw 'Java client vs Rust server failed' }
} else {
  Write-Host 'cargo not found: skipping Java-vs-Rust end-to-end check'
}
