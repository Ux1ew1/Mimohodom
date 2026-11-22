$ErrorActionPreference = 'Stop'
# Paid probe: one real review request (stage 5 prompt with kinds) to Polza.AI.
# Usage: .\scripts\probe-review-polza.ps1 [model]   (default google/gemini-2.5-flash-lite)
$projectRoot = Split-Path $PSScriptRoot -Parent
$model = if ($args.Count -gt 0) { $args[0] } else { 'google/gemini-2.5-flash-lite' }
if ($env:JAVA_HOME) { $javaRoot = $env:JAVA_HOME } else { $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName }
$jsonJar = "$projectRoot/.tools/json.jar"
$out = "$projectRoot/.tools/review-probe"
New-Item -ItemType Directory -Force $out | Out-Null
$src = "$projectRoot/app/src/main/java/app/fastnotes"
$sources = @(
  "$src/Note.java",
  "$src/ManagerAnalysis.java",
  "$src/ReviewAnalysis.java",
  "$src/Analysis.java",
  "$src/JsonRepair.java",
  "$src/FolderPolicy.java",
  "$src/AgentAction.java",
  "$src/AgentPlan.java",
  "$src/PlanTree.java",
  "$src/ReminderPolicy.java",
  "$src/Metadata.java",
  "$src/Retrieval.java",
  "$src/TopicSelection.java",
  "$src/TaskKind.java",
  "$projectRoot/tests/ReviewProbe.java"
)
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$jsonJar" app.fastnotes.ReviewProbe payload $model "$out/payload.json"
if ($LASTEXITCODE -ne 0) { throw 'Payload failed' }
$secure = Read-Host 'Polza.AI key (input hidden)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:POLZA_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try { & node "$projectRoot/scripts/probe-review-polza.mjs" "$out/payload.json" "$out/response.json" }
finally { Remove-Item Env:POLZA_KEY -ErrorAction SilentlyContinue }
if ($LASTEXITCODE -ne 0) { throw 'Request failed' }
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$jsonJar" app.fastnotes.ReviewProbe parse "$out/response.json"
