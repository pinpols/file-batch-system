# FE 自动部署:每分钟由 BatchDeployFE 计划任务触发。
# 同 deploy-be.ps1 但指向 FE 仓 + main 分支(FE 的 docker-compose 在 main)。

$ErrorActionPreference = 'Continue'

$Repo    = 'C:\Users\aa\Downloads\batch-console'
$Branch  = 'main'
$LogDir  = 'C:\Users\aa\logs'
$LogFile = "$LogDir\deploy-fe.log"
$Lock    = "$LogDir\deploy-fe.lock"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

if (Test-Path $Lock) {
  $age = (Get-Date) - (Get-Item $Lock).LastWriteTime
  if ($age.TotalMinutes -lt 30) { exit 0 }
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType File -Path $Lock -Force | Out-Null

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Out-File -Append -Encoding utf8 $LogFile }

try {
  Set-Location $Repo

  $fetchOut = git fetch origin $Branch 2>&1
  if ($LASTEXITCODE -ne 0) { Log "FETCH FAILED: $fetchOut"; return }

  $local  = (git rev-parse HEAD).Trim()
  $remote = (git rev-parse "origin/$Branch").Trim()

  if ($local -eq $remote) { return }

  Log "UPDATE detected: $($local.Substring(0,8)) -> $($remote.Substring(0,8))"

  $pullOut = git pull --ff-only origin $Branch 2>&1
  Log "git pull: $pullOut"
  if ($LASTEXITCODE -ne 0) { Log "PULL FAILED (rc=$LASTEXITCODE),abort"; return }

  Log "docker compose build + up start"
  # -p batch-platform-console:与已跑 stack 同 project namespace(容器名 batch-console 复用而非新建撞名)
  $composeOut = docker compose -p batch-platform-console -f docker-compose.yml up -d --build --wait --wait-timeout 300 2>&1
  $rc = $LASTEXITCODE
  $composeOut | Out-File -Append -Encoding utf8 $LogFile
  Log "compose finished rc=$rc"
}
catch {
  Log "EXCEPTION: $_"
}
finally {
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
}
