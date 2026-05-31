param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Modules)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"
Import-DotEnv (Join-Path $root $composeEnvFile)
$env:BATCH_TIMEZONE_DEFAULT_ZONE = Get-EnvValue "BATCH_TIMEZONE_DEFAULT_ZONE" "Asia/Shanghai"
$env:TZ = $env:BATCH_TIMEZONE_DEFAULT_ZONE
$env:BATCH_LOCALE = Get-EnvValue "BATCH_LOCALE" "C.UTF-8"
$env:LANG = $env:BATCH_LOCALE
$env:LC_ALL = $env:BATCH_LOCALE
$env:BATCH_CONSOLE_READ_REPLICA_ENABLED = Get-EnvValue "BATCH_CONSOLE_READ_REPLICA_ENABLED" "true"

if (-not $Modules -or $Modules.Count -eq 0) {
  Write-Host "用法: .\scripts\ps1\local\restart.ps1 <module> [module...]"
  Write-Host "支持: orchestrator trigger console worker-import worker-export worker-process worker-dispatch worker-atomic"
  exit 1
}

$logDir = Join-Path $root "logs\app"
$runtimeJarDir = Join-Path $root "build\runtime-jars"
$pidFile = Join-Path $root "logs\start-all.pids"
Ensure-Directory $logDir
Ensure-Directory $runtimeJarDir

foreach ($name in $Modules) { [void](Get-AppPort $name) }

Write-Host "==> 停止目标模块..."
foreach ($name in $Modules) {
  Stop-PortProcess -Name $name -Port ([int](Get-AppPort $name))
}
Start-Sleep -Seconds 2

if ((Get-EnvValue "BUILD" "0") -eq "1") {
  Write-Host "==> 构建目标模块（BUILD=1）..."
  $mvn = Get-MavenCommand
  Enable-MavenNativeAccessForNewJdk
  foreach ($name in $Modules) {
    $mod = Get-MavenModuleForApp $name
    Write-Host "  构建 $mod ..."
    & $mvn @("-pl", $mod, "-am", "clean", "package", "-DskipTests", "-q")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = Get-ChildItem "$mod\target" -Filter "$mod-*-exec.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) {
      $jar = Get-ChildItem "$mod\target" -Filter "$mod-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "sources|javadoc|\.original$|-exec\.jar$" } |
        Select-Object -First 1
    }
    if (-not $jar) { throw "未找到 $mod 的 jar" }
    Copy-Item $jar.FullName (Join-Path $runtimeJarDir "$name.jar") -Force
    Write-Host "  构建完成 -> build/runtime-jars/$name.jar"
  }
}

Write-Host "==> 按依赖顺序启动..."
$ordered = @("orchestrator", "trigger", "console", "worker-import", "worker-export", "worker-process", "worker-dispatch", "worker-atomic")
$needWaitOrch = $Modules -contains "orchestrator"
foreach ($name in $ordered) {
  if ($Modules -notcontains $name) { continue }
  Start-JavaApp -Name $name -Jar (Join-Path $runtimeJarDir "$name.jar") -LogDir $logDir -PidFile $pidFile
  if ($name -eq "orchestrator" -and $needWaitOrch) {
    $port = Get-AppPort "orchestrator"
    Wait-HttpUp -Name "orchestrator" -Url "http://127.0.0.1:$port/actuator/health" -Rounds 60 -IntervalSeconds 3
  }
}

Write-Host ""
Write-Host "重启完成。"
