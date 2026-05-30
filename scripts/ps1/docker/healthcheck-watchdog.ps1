# 容器健康看门狗:每 2 min 由 BatchHealthcheck 计划任务触发。
# 行为:docker ps 拉 batch-* 容器状态,与上次比对,只在"healthy → 非 healthy"转换时发 webhook;状态恢复时发 resolved。
# 共用环境变量 BATCH_DEPLOY_NOTIFY_WEBHOOK(与 deploy 脚本同渠道)。

$ErrorActionPreference = 'Continue'

$LogDir    = 'C:\Users\aa\logs'
$LogFile   = "$LogDir\healthcheck.log"
$StateFile = "$LogDir\healthcheck-state.json"
$Webhook   = $env:BATCH_DEPLOY_NOTIFY_WEBHOOK
$MaxLogBytes = 10MB

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt $MaxLogBytes)) {
  if (Test-Path "$LogFile.1") { Remove-Item "$LogFile.1" -Force -ErrorAction SilentlyContinue }
  Move-Item $LogFile "$LogFile.1" -Force -ErrorAction SilentlyContinue
}

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Out-File -Append -Encoding utf8 $LogFile }

function Notify($title, $detail) {
  if (-not $Webhook) { return }
  try {
    $body = @{ msgtype='text'; text=@{ content="[batch healthcheck] $title`n$detail`nhost=$env:COMPUTERNAME time=$(Get-Date -Format s)" } } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Method Post -Uri $Webhook -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10 | Out-Null
  } catch { Log "NOTIFY FAILED: $_" }
}

$psOutput = docker ps -a --filter 'name=batch-' --format '{{.Names}}|{{.State}}|{{.Status}}' 2>$null
if (-not $psOutput) { Log "docker ps 无输出"; return }

$current = @{}
foreach ($line in $psOutput) {
  if (-not $line.Trim()) { continue }
  # 跳过 *-init 容器(by-design 跑一次 exit 0,不是异常)
  if ($line -match '^batch-\w+-init\|') { continue }
  $parts = $line.Split('|')
  $name = $parts[0]
  $state = $parts[1]
  $status = $parts[2]
  $health = if ($status -match '\(healthy\)') { 'healthy' }
            elseif ($status -match '\(unhealthy\)') { 'unhealthy' }
            elseif ($status -match '\(health: starting\)') { 'starting' }
            elseif ($state -eq 'running') { 'running' }
            else { $state }
  $current[$name] = $health
}

$previous = @{}
if (Test-Path $StateFile) {
  try { (Get-Content $StateFile -Raw | ConvertFrom-Json).psobject.properties | ForEach-Object { $previous[$_.Name] = $_.Value } } catch {}
}

$alerts = @()
foreach ($n in (($current.Keys + $previous.Keys) | Sort-Object -Unique)) {
  $now = $current[$n]
  $was = $previous[$n]
  if ($was -eq 'healthy' -and $now -ne 'healthy') {
    $alerts += "X ${n}: $was -> $now"
  } elseif ($was -and $was -ne 'healthy' -and $now -eq 'healthy') {
    $alerts += "OK ${n}: $was -> healthy (resolved)"
  } elseif (-not $was -and ($now -in 'unhealthy','exited','restarting')) {
    $alerts += "X ${n}: new + $now"
  }
}

if ($alerts.Count -gt 0) {
  $msg = $alerts -join "`n"
  Log "STATE CHANGE:`n$msg"
  Notify "container state change" $msg
}

$current | ConvertTo-Json -Depth 2 | Out-File -Encoding utf8 $StateFile
