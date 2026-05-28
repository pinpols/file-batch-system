# BE 自动部署 + 自动 main → docker-deploy sync。
# 每分钟由 BatchDeployBE 计划任务触发(VBS wrapper 隐藏窗口)。
#
# 行为(每次跑):
#   1) git fetch origin main feature/docker-deploy
#   2) 若 main 有新 commit(origin/main 不是 origin/feature/docker-deploy 祖先)
#      → checkout feature/docker-deploy,merge origin/main(--no-edit),push 回 origin
#      (失败/冲突 → log + 通知 + 中止,人工解决)
#   3) 比 local HEAD vs origin/feature/docker-deploy(可能刚被自己 sync 推进了)
#   4) 不同 → git pull --ff-only + docker compose up -d --build --wait
#
# 日志:C:\Users\aa\logs\deploy-be.log(>10MB 自动 rotate)
# 失败通知:env BATCH_DEPLOY_NOTIFY_WEBHOOK(钉钉/企微/Slack 兼容)
# 排障:Get-Content C:\Users\aa\logs\deploy-be.log -Wait

$ErrorActionPreference = 'Continue'

$Repo        = 'C:\Users\aa\Downloads\file-batch-system'
$DeployBr    = 'feature/docker-deploy'
$MainBr      = 'main'
$LogDir      = 'C:\Users\aa\logs'
$LogFile     = "$LogDir\deploy-be.log"
$Lock        = "$LogDir\deploy-be.lock"
$Webhook     = $env:BATCH_DEPLOY_NOTIFY_WEBHOOK
$MaxLogBytes = 10MB

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

# Log rotation
if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt $MaxLogBytes)) {
  if (Test-Path "$LogFile.1") { Remove-Item "$LogFile.1" -Force -ErrorAction SilentlyContinue }
  Move-Item $LogFile "$LogFile.1" -Force -ErrorAction SilentlyContinue
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
    $body = @{ msgtype='text'; text=@{ content="[BE deploy] $title`n$detail`nhost=$env:COMPUTERNAME time=$(Get-Date -Format s)" } } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Method Post -Uri $Webhook -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10 | Out-Null
  } catch { Log "NOTIFY FAILED: $_" }
}

try {
  Set-Location $Repo

  # 必须在 deploy 分支上才能跑 sync + 部署(防误改其它分支)
  $currentBr = (git symbolic-ref --short HEAD 2>&1).Trim()
  if ($currentBr -ne $DeployBr) {
    Log "ABORT: 当前分支 $currentBr,需在 $DeployBr"
    Notify 'aborted' "current branch=$currentBr expected=$DeployBr"
    return
  }
  if ((git status --porcelain 2>&1).Length -gt 0) {
    Log "ABORT: 工作树有未提交改动,需人工 commit/stash"
    Notify 'aborted' "working tree dirty,manual cleanup needed"
    return
  }

  # 1) fetch 两个分支
  $fetchOut = git fetch origin $MainBr $DeployBr 2>&1
  if ($LASTEXITCODE -ne 0) {
    Log "FETCH FAILED: $fetchOut"
    Notify 'fetch failed' "$fetchOut"
    return
  }

  # 2) 检测 main 有没有未 sync 到 docker-deploy 的 commit
  #    若 origin/main 不是 origin/feature/docker-deploy 的祖先 → main 有 docker-deploy 没的 commit → 需 sync
  $mergeBase = (git merge-base "origin/$MainBr" "origin/$DeployBr" 2>&1).Trim()
  $mainHead  = (git rev-parse "origin/$MainBr" 2>&1).Trim()
  if ($mergeBase -ne $mainHead) {
    # main 有新 commit 没合进 docker-deploy → 自动 sync
    Log "SYNC needed: origin/$MainBr ahead of origin/$DeployBr,auto-merging"

    $mergeOut = git merge --no-edit "origin/$MainBr" 2>&1
    if ($LASTEXITCODE -ne 0) {
      Log "MERGE CONFLICT: $mergeOut"
      git merge --abort 2>&1 | Out-Null
      Notify 'sync merge conflict' "$mergeOut`n人工解决:cd $Repo; git checkout $DeployBr; git merge origin/$MainBr"
      return
    }
    Log "git merge ok"

    $pushOut = git push origin $DeployBr 2>&1
    if ($LASTEXITCODE -ne 0) {
      Log "PUSH FAILED after merge: $pushOut(本地已 merge,远程未同步,下轮重试)"
      Notify 'sync push failed' "$pushOut"
      # 不 return,后面 deploy 还能用本地新 HEAD
    } else {
      Log "git push ok: synced main into $DeployBr"
    }
  }

  # 3) 比 local HEAD vs origin(可能刚被自己 sync 推进了)
  $local  = (git rev-parse HEAD).Trim()
  $remote = (git rev-parse "origin/$DeployBr").Trim()

  if ($local -eq $remote) { return }   # 无更新,秒退

  Log "UPDATE detected: $($local.Substring(0,8)) -> $($remote.Substring(0,8))"

  $pullOut = git pull --ff-only origin $DeployBr 2>&1
  Log "git pull: $pullOut"
  if ($LASTEXITCODE -ne 0) {
    Log "PULL FAILED (rc=$LASTEXITCODE),abort"
    Notify 'pull failed' "$pullOut"
    return
  }

  # 4) docker compose build + up
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
