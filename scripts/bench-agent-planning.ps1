param(
  [switch]$DryRun,
  [switch]$PromptForKey,
  [string]$Resume
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$javaRoot = $env:JAVA_HOME
if (-not $javaRoot) {
  $javaRoot = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
}
$jsonJar = "$projectRoot/.tools/json.jar"
$out = "$projectRoot/.tools/agent-planning-bench"
$src = "$projectRoot/app/src/main/java/app/fastnotes"
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(
  'AgentChatPrompt', 'PlanChat', 'NoteEdit', 'ReminderBatch', 'PlanTree', 'ReminderPolicy',
  'Note', 'FolderPolicy', 'Metadata', 'Retrieval', 'AgentAction', 'AgentPlan', 'JsonRepair',
  'TaskKind', 'TopicSelection', 'ManagerAnalysis', 'Analysis', 'ReviewAnalysis', 'CommandAnalysis'
) | ForEach-Object { "$src/$_.java" }
& "$javaRoot/bin/javac.exe" -encoding UTF-8 -cp $jsonJar -d $out $sources "$projectRoot/tests/AgentPlanningCases.java"
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
$cases = "$out/cases.json"
& "$javaRoot/bin/java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$jsonJar" app.fastnotes.AgentPlanningCases $cases
if ($LASTEXITCODE -ne 0) { throw 'Benchmark payload generation failed' }

$nodeArgs = @("$PSScriptRoot/bench-agent-planning.mjs", $cases, "$out/results-$(Get-Date -Format yyyyMMdd-HHmmss).json")
if ($DryRun) { $nodeArgs += '--dry' }
if ($Resume) { $nodeArgs += "--resume=$((Resolve-Path -LiteralPath $Resume).Path)" }
$prompted = -not $DryRun -and $PromptForKey -and -not $env:POLZA_KEY
if ($prompted) {
  $secure = Read-Host 'Polza.AI key (input hidden)' -AsSecureString
  $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { $env:POLZA_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
  finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $secure.Dispose()
  }
}
try { & node @nodeArgs }
finally { if ($prompted) { Remove-Item Env:POLZA_KEY -ErrorAction SilentlyContinue } }
if ($LASTEXITCODE -ne 0) { throw 'Planning benchmark failed' }
