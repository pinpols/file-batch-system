# BE 自动部署:每分钟由 BatchDeployBE 计划任务触发。
# 行为:fetch → 比较 HEAD → 有更新就 pull + compose up -d --build --wait。
# 日志:C:\Users\aa\logs\deploy-be.log。
# 排障:Get-Content C:\Users\aa\logs\deploy-be.log -Wait

$ErrorActionPreference = 'Continue'

$Repo    = 'C:\Users\aa\Downloads\file-batch-system'
$Branch  = 'feature/docker-deploy'
$LogDir  = 'C:\Users\aa\logs'
$LogFile = "$LogDir\deploy-be.log"
$Lock    = "$LogDir\deploy-be.lock"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

# 同时只跑一份(防计划任务和手工 trigger 撞车)
if (Test-Path $Lock) {
  $age = (Get-Date) - (Get-Item $Lock).LastWriteTime
  if ($age.TotalMinutes -lt 30) { exit 0 }   # 30 min 内的 lock 还有效,这次跳过
  # 30 min 还在 = 死锁,清掉
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType File -Path $Lock -Force | Out-Null

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Out-File -Append -Encoding utf8 $LogFile }

try {
  Set-Location $Repo

  # fetch 静默(stderr 也吞,只在出错时记录)
  $fetchOut = git fetch origin $Branch 2>&1
  if ($LASTEXITCODE -ne 0) { Log "FETCH FAILED: $fetchOut"; return }

  $local  = (git rev-parse HEAD).Trim()
  $remote = (git rev-parse "origin/$Branch").Trim()

  if ($local -eq $remote) { return }   # 没更新,秒退;不污染日志

  Log "UPDATE detected: $($local.Substring(0,8)) -> $($remote.Substring(0,8))"

  $pullOut = git pull --ff-only origin $Branch 2>&1
  Log "git pull: $pullOut"
  if ($LASTEXITCODE -ne 0) { Log "PULL FAILED (rc=$LASTEXITCODE),abort"; return }

  Log "docker compose build + up start"
  $composeOut = docker compose --env-file .env.local `
    -f docker-compose.yml -f docker/compose/app.yml -f docker/compose/app.deploy.yml `
    --profile apps --profile replica `
    up -d --build --wait --wait-timeout 600 2>&1
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
