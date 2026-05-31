param()
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
$pidFile = Join-Path $root "logs\start-all.pids"
$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"

$phases = @(
  @("阶段 1/3：停止 worker-import / worker-export / worker-process / worker-dispatch / worker-atomic", @("worker-import", "worker-export", "worker-process", "worker-dispatch", "worker-atomic"))),
  @("阶段 2/3：停止 trigger / console", @("trigger", "console")),
  @("阶段 3/3：停止 orchestrator", @("orchestrator"))
)

Write-Host "==> 停止 Spring Boot 进程（先 worker，再 trigger/console，最后 orchestrator）..."
$pidLines = @()
if (Test-Path $pidFile) {
  $pidLines = Get-Content $pidFile | Where-Object { $_.Trim() }
  Remove-Item $pidFile -Force
}

$killed = 0
foreach ($phase in $phases) {
  Write-Host "==> $($phase[0])"
  foreach ($name in $phase[1]) {
    foreach ($line in $pidLines) {
      $parts = $line -split "`t| +", 3
      if ($parts.Count -ge 2 -and $parts[0] -eq $name) {
        $processId = [int]$parts[1]
        $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($proc) {
          Write-Host "  kill $name (pid=$processId)"
          Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
          $killed++
        }
      }
    }
    Stop-PortProcess -Name $name -Port ([int](Get-AppPort $name))
  }
  Start-Sleep -Seconds ([int](Get-EnvValue "STOP_WAIT_SEC" "5"))
}

Get-JavaBatchProcesses | ForEach-Object {
  Write-Host "  残留进程 kill pid=$($_.ProcessId)"
  Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  $script:killed++
}

if ($killed -eq 0) {
  Write-Host "  未发现运行中的 batch 进程。"
} else {
  Write-Host "已完成清理的进程数（累计）: $killed"
}

if ((Get-EnvValue "STOP_DOCKER" "") -eq "1") {
  Write-Host "==> Docker Compose 停止（STOP_DOCKER=1，保持环境不删除）..."
  Ensure-DockerDaemon
  & docker compose --env-file $composeEnvFile stop
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
  Write-Host "提示：仅停止 Java 进程；基础依赖仍在运行。停止容器请执行: .\scripts\ps1\docker\down-apps.ps1"
}
