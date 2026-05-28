# BE 自动部署:每分钟由 BatchDeployBE 计划任务触发。
# 行为:fetch → 比较 HEAD → 有更新就 pull + compose up -d --build --wait。
# 日志:C:\Users\aa\logs\deploy-be.log。超 10MB 自动 rotate 到 .1。
# 失败通知:设环境变量 BATCH_DEPLOY_NOTIFY_WEBHOOK(钉钉/企微/Slack 兼容,POST {text:"..."}),fail 时推送。
# 排障:Get-Content C:\Users\aa\logs\deploy-be.log -Wait

$ErrorActionPreference = 'Continue'

$Repo    = 'C:\Users\aa\Downloads\file-batch-system'
$Branch  = 'feature/docker-deploy'
$LogDir  = 'C:\Users\aa\logs'
$LogFile = "$LogDir\deploy-be.log"
$Lock    = "$LogDir\deploy-be.lock"
$Webhook = $env:BATCH_DEPLOY_NOTIFY_WEBHOOK
$MaxLogBytes = 10MB

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

# Log rotation
if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt $MaxLogBytes)) {
  $rotated = "$LogFile.1"
  if (Test-Path $rotated) { Remove-Item $rotated -Force -ErrorAction SilentlyContinue }
  Move-Item $LogFile $rotated -Force -ErrorAction SilentlyContinue
}

# 并发锁
if (Test-Path $Lock) {
  $age = (Get-Date) - (Get-Item $Lock).LastWriteTime
  if ($age.TotalMinutes -lt 30) { exit 0 }
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType File -Path $Lock -Force | Out-Null

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Out-File -Append -Encoding utf8 $LogFile }

function Notify($title, $detail) {
  if (-not $Webhook) { return }
  try {
    $body = @{ msgtype = 'text'; text = @{ content = "[BE deploy] $title`n$detail`nhost=$env:COMPUTERNAME time=$(Get-Date -Format s)" } } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Method Post -Uri $Webhook -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10 | Out-Null
  } catch { Log "NOTIFY FAILED: $_" }
}

try {
  Set-Location $Repo
  $fetchOut = git fetch origin $Branch 2>&1
  if ($LASTEXITCODE -ne 0) {
    Log "FETCH FAILED: $fetchOut"
    Notify 'fetch failed' "$fetchOut"
    return
  }
  $local  = (git rev-parse HEAD).Trim()
  $remote = (git rev-parse "origin/$Branch").Trim()
  if ($local -eq $remote) { return }

  Log "UPDATE detected: $($local.Substring(0,8)) -> $($remote.Substring(0,8))"

  $pullOut = git pull --ff-only origin $Branch 2>&1
  Log "git pull: $pullOut"
  if ($LASTEXITCODE -ne 0) {
    Log "PULL FAILED (rc=$LASTEXITCODE),abort"
    Notify 'pull failed' "$pullOut"
    return
  }

  Log "docker compose build + up start"
  $composeOut = docker compose --env-file .env.local `
    -f docker-compose.yml -f docker/compose/app.yml -f docker/compose/app.deploy.yml `
    --profile apps --profile replica `
    up -d --build --wait --wait-timeout 600 2>&1
  $rc = $LASTEXITCODE
  $composeOut | Out-File -Append -Encoding utf8 $LogFile
  Log "compose finished rc=$rc"
  if ($rc -ne 0) {
    $tail = ($composeOut | Select-Object -Last 15) -join "`n"
    Notify "compose failed rc=$rc" "sha=$($remote.Substring(0,8))`n--- last log ---`n$tail"
  }
}
catch {
  Log "EXCEPTION: $_"
  Notify 'exception' "$_"
}
finally {
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
}
