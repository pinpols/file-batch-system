param(
  [switch]$Default,
  [switch]$Unit,
  [switch]$It,
  [switch]$E2e,
  [switch]$All,
  [switch]$BuildOnly,
  [switch]$SkipBuild,
  [Parameter(ValueFromRemainingArguments = $true)][string[]]$ExtraMavenArgs
)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = Get-EnvValue "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" "//./pipe/docker_engine"
$env:DOCKER_API_VERSION = Get-EnvValue "DOCKER_API_VERSION" "1.44"
$mavenThreads = Get-EnvValue "MAVEN_THREADS" "1"
$mvn = Get-MavenCommand
Enable-MavenNativeAccessForNewJdk

$mode = "default"
if ($Unit) { $mode = "unit" }
elseif ($It) { $mode = "it" }
elseif ($E2e) { $mode = "e2e" }
elseif ($All) { $mode = "all" }
elseif ($BuildOnly) { $mode = "build-only" }

$logDir = Join-Path $root "logs\test"
Ensure-Directory $logDir
$coreModules = @(
  "batch-common", "batch-trigger", "batch-orchestrator", "batch-worker-core",
  "batch-worker-import", "batch-worker-export", "batch-worker-process",
  "batch-worker-dispatch", "batch-worker-spi", "batch-console-api"
)
$failed = 0
$passed = 0
$results = New-Object System.Collections.Generic.List[string]

function Invoke-Maven {
  param([string[]]$ArgsList)
  $cmd = @("-T", $mavenThreads, "--no-transfer-progress") + $ArgsList + $ExtraMavenArgs
  Write-Host "> $mvn $($cmd -join ' ')"
  & $mvn @cmd
  return $LASTEXITCODE
}

function Invoke-BuildCore {
  $mods = $coreModules -join ","
  return Invoke-Maven @("clean", "install", "-pl", $mods, "-DskipTests")
}

function Invoke-ModuleTests {
  param([string]$Selector, [string[]]$Modules)
  $overall = 0
  foreach ($module in $Modules) {
    Write-Host ""
    Write-Host "== 测试模块: $module"
    $args = @("test", "-pl", $module, "-Dsurefire.failIfNoSpecifiedTests=false")
    if ($Selector) { $args += "-Dtest=$Selector" }
    if ($SkipBuild) { $args += "-Dmaven.compiler.skip=true" }
    if ((Invoke-Maven $args) -ne 0) { $overall = 1 }
  }
  return $overall
}

function Record-Result {
  param([string]$Name, [int]$Code)
  if ($Code -eq 0) {
    $script:passed++
    $script:results.Add("$Name=PASSED")
  } else {
    $script:failed++
    $script:results.Add("$Name=FAILED")
  }
}

if (-not $SkipBuild) {
  Get-ChildItem $root -Recurse -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "\\target\\(surefire-reports|failsafe-reports)$" } |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

switch ($mode) {
  "build-only" {
    Write-Host "==> 构建所有核心模块（跳过测试）"
    exit (Invoke-BuildCore)
  }
  "unit" {
    $code = 0
    if (-not $SkipBuild) { $code = Invoke-BuildCore }
    if ($code -eq 0) { $code = Invoke-ModuleTests '!*IntegrationTest,!*IT,!PartitionLeaseReclaimSchedulerTest' $coreModules }
    Record-Result "UNIT_TESTS" $code
  }
  "it" {
    $code = 0
    if (-not $SkipBuild) { $code = Invoke-BuildCore }
    if ($code -eq 0) { $code = Invoke-ModuleTests '*IntegrationTest,*IT' $coreModules }
    Record-Result "INTEGRATION_TESTS" $code
  }
  "e2e" {
    $code = 0
    if (-not $SkipBuild) { $code = Invoke-BuildCore }
    if ($code -eq 0) { $code = Invoke-Maven @("test", "-pl", "batch-e2e-tests", "-Dsurefire.failIfNoSpecifiedTests=false") }
    Record-Result "E2E_TESTS" $code
  }
  "all" {
    $code = 0
    if (-not $SkipBuild) { $code = Invoke-BuildCore }
    if ($code -eq 0) { $code = Invoke-ModuleTests "" $coreModules }
    Record-Result "UNIT_INTEGRATION_TESTS" $code
    $e2eCode = Invoke-Maven @("test", "-pl", "batch-e2e-tests", "-Dsurefire.failIfNoSpecifiedTests=false")
    Record-Result "E2E_TESTS" $e2eCode
  }
  default {
    $code = 0
    if (-not $SkipBuild) { $code = Invoke-BuildCore }
    if ($code -eq 0) { $code = Invoke-ModuleTests "" $coreModules }
    Record-Result "DEFAULT_TESTS" $code
  }
}

Write-Host ""
Write-Host "== 测试执行总结"
$results | ForEach-Object { Write-Host $_ }
Write-Host "PASSED: $passed | FAILED: $failed"
exit ($(if ($failed -eq 0) { 0 } else { 1 }))
