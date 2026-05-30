<#
.SYNOPSIS
  把 origin/main 的更新合并到本地 feature/docker-deploy(Windows / PowerShell 7+)。

.DESCRIPTION
  与 scripts/local/sync-main.sh 行为一致的 PowerShell 版本。
  仅做 git 操作,不触发部署。部署用 scripts/ps1/docker/deploy.ps1。

  首次同步若两边无共同祖先(本地是快照仓库),会自动加 --allow-unrelated-histories,
  一次性引入 origin/main 全部内容,生成大 merge commit。后续 sync 行为正常。

  合并成功不自动 push;部署验证通过后再手动 git push origin feature/docker-deploy。

.PARAMETER Yes
  跳过交互确认(等价于 sh 的 --yes)。

.PARAMETER DeployBranch
  目标分支,默认 feature/docker-deploy。

.PARAMETER MainBranch
  来源分支,默认 main。

.PARAMETER Remote
  remote 名,默认 origin。

.EXAMPLE
  ./scripts/ps1/local/sync-main.ps1
  ./scripts/ps1/local/sync-main.ps1 -Yes
#>
[CmdletBinding()]
param(
    [switch]$Yes,
    [string]$DeployBranch = "feature/docker-deploy",
    [string]$MainBranch = "main",
    [string]$Remote = "origin"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Set-Location $Root

# --- 1. 前置校验 ---
$current = (git rev-parse --abbrev-ref HEAD).Trim()
if ($current -ne $DeployBranch) {
    Write-Error "当前分支是 '$current',需在 '$DeployBranch' 上才能同步。先 git checkout $DeployBranch"
}

$dirty = git status --porcelain
if ($dirty) {
    Write-Host "ERR: 工作树有未提交改动,先 commit 或 stash。当前状态:" -ForegroundColor Red
    git status --short
    exit 1
}

git remote get-url $Remote *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Error "remote '$Remote' 不存在。先 git remote add $Remote <URL>。"
}

# --- 2. fetch + 校验 origin/main ---
Write-Host "==> fetch $Remote..." -ForegroundColor Cyan
git fetch $Remote --prune
if ($LASTEXITCODE -ne 0) { throw "fetch 失败" }

git rev-parse "$Remote/$MainBranch" *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Error "$Remote/$MainBranch 不存在,远端没有 main 分支。"
}

# --- 3. 显示本次合并范围 ---
$headSha = (git rev-parse --short HEAD).Trim()
$headMsg = (git log -1 --format=%s).Trim()
$targetSha = (git rev-parse --short "$Remote/$MainBranch").Trim()
$targetMsg = (git log -1 --format=%s "$Remote/$MainBranch").Trim()

Write-Host ""
Write-Host "==> 准备合并 $Remote/$MainBranch → $DeployBranch" -ForegroundColor Cyan
Write-Host "    当前 HEAD     : $headSha $headMsg"
Write-Host "    合并目标 HEAD : $targetSha $targetMsg"

# --- 4. 共同祖先 / unrelated-histories 检测 ---
$unrelated = @()
$base = git merge-base HEAD "$Remote/$MainBranch" 2>$null
if (-not $base) {
    Write-Host ""
    Write-Host "⚠️  无共同祖先 (unrelated histories)" -ForegroundColor Yellow
    Write-Host "    本地 $DeployBranch 与 $Remote/$MainBranch 历史不相关。" -ForegroundColor Yellow
    Write-Host "    将加 --allow-unrelated-histories,合并会一次性引入 origin/main 全部内容," -ForegroundColor Yellow
    Write-Host "    生成 1 个大 merge commit。后续 sync 行为正常。" -ForegroundColor Yellow
    $unrelated = @("--allow-unrelated-histories")
} else {
    $baseSha = ($base | Out-String).Trim().Substring(0, 7)
    Write-Host "    共同祖先     : $baseSha"
}

# diff 摘要
$statLine = (git diff "HEAD..$Remote/$MainBranch" --stat | Select-Object -Last 1)
Write-Host ""
Write-Host "==> diff 规模 (HEAD..$Remote/$MainBranch): $statLine"

# --- 5. 确认 ---
if (-not $Yes) {
    Write-Host ""
    $ans = Read-Host "继续合并? [y/N]"
    if ($ans -ne "y" -and $ans -ne "Y") {
        Write-Host "取消。"
        exit 0
    }
}

# --- 6. 合并 ---
Write-Host ""
Write-Host "==> merging..." -ForegroundColor Cyan
$msg = "sync: merge $Remote/$MainBranch into $DeployBranch ($(Get-Date -Format yyyy-MM-dd))"
git merge @unrelated --no-edit -m $msg "$Remote/$MainBranch"
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERR: merge 有冲突。看 git status,手动解决后:" -ForegroundColor Red
    Write-Host "       git add <files>"
    Write-Host "       git commit  # (msg 会用上面那条)"
    exit 1
}

# --- 7. 完成提示 ---
$newHead = (git rev-parse --short HEAD).Trim()
Write-Host ""
Write-Host "✔ 合并完成。HEAD: $newHead" -ForegroundColor Green
Write-Host ""
Write-Host "  下一步:"
Write-Host "    1) 跑构建/启动验证:"
Write-Host "         ./scripts/ps1/docker/deploy.ps1"
Write-Host "    2) 容器全部 healthy 之后再 push:"
Write-Host "         git push $Remote $DeployBranch"
Write-Host "    3) 验证失败 → git reset --hard HEAD~1 撤回本次 sync,排查后重做。"
