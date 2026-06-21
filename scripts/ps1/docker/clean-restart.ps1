<#
.SYNOPSIS
  全 Docker 模式：彻底重启（删容器+卷[清数据] + 无缓存重建镜像 + 重新拉起）。

.DESCRIPTION
  Windows / PowerShell 版 clean-restart，等价于 `make dev-restart-clean` 的 Docker 版
  兜底方案。执行步骤：
    1) docker compose down -v   删除本项目所有容器、网络与【数据卷】
    2) docker compose build --no-cache   清缓存全量重建应用镜像
    3) docker compose up -d --wait        重新拉起依赖 + 应用并等待健康

  适用：构建缓存疑似脏、删改类/资源残留、需要全新空库、怀疑"缓存或残留状态问题"。

  ⚠️ 危险：步骤 1 的 down -v 会删除 Postgres / MinIO / Kafka / Redis 等数据卷，
     所有持久化数据丢失，不可恢复。默认需输入 yes 二次确认。

.PARAMETER Yes
  跳过删除数据卷的二次确认（用于自动化 / CI）。

.PARAMETER KeepCache
  保留 Docker 构建缓存（默认 --no-cache 全量重建）。

.PARAMETER SkipWait
  up 后不等待健康检查（默认 --wait 直到全部 healthy，超时 300s）。

.EXAMPLE
  .\scripts\ps1\docker\clean-restart.ps1

.EXAMPLE
  .\scripts\ps1\docker\clean-restart.ps1 -Yes -KeepCache
#>
param(
  [switch]$Yes,
  [switch]$KeepCache,
  [switch]$SkipWait
)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\..\local\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$ctx = Initialize-AppComposeEnv
$composeArgs = Get-AppComposeArgs -EnvFile $ctx.EnvFile

if (-not $Yes) {
  Write-Warning "clean-restart 将对项目 '$($ctx.ProjectName)' 执行 docker compose down -v："
  Write-Warning "  删除全部容器、网络与【数据卷】，Postgres/MinIO/Kafka/Redis 持久化数据将丢失，不可恢复。"
  $ans = Read-Host "确认继续？输入 yes 确认（其它任意输入取消）"
  if ($ans -ne "yes") { Write-Host "已取消。"; exit 1 }
}

Write-Host "==> [1/3] 停止并删除容器 + 网络 + 数据卷（down -v）..."
& docker compose @composeArgs down -v --remove-orphans
if ($LASTEXITCODE -ne 0) {
  Write-Warning "down -v 返回非 0（exit=$LASTEXITCODE）；可能本就未启动，继续。"
}

Ensure-AppNetwork -NetworkName $ctx.NetworkName
Ensure-Directory (Join-Path $root "logs\docker")

$buildArgs = @("build")
if (-not $KeepCache) {
  $buildArgs += "--no-cache"
  Write-Host "==> [2/3] 无缓存全量重建应用镜像（--no-cache，较慢）..."
} else {
  Write-Host "==> [2/3] 重建应用镜像（保留缓存，-KeepCache）..."
}
& docker compose @composeArgs @buildArgs
if ($LASTEXITCODE -ne 0) { throw "镜像构建失败（exit=$LASTEXITCODE）" }

Write-Host "==> [3/3] 重新拉起基础依赖 + 应用容器..."
$upArgs = @("up", "-d", "--force-recreate", "--remove-orphans")
if (-not $SkipWait) { $upArgs += @("--wait", "--wait-timeout", "300") }
& docker compose @composeArgs @upArgs
$upExit = $LASTEXITCODE
if ($upExit -ne 0) {
  Write-Warning "compose up 返回非 0（exit=$upExit）。当前状态如下，便于排查："
  & docker compose @composeArgs ps
  throw "重启未完全就绪（exit=$upExit）。排查：docker compose $($composeArgs -join ' ') logs <service>，或 logs/docker/*.log"
}

Write-Host ""
Write-Host "==> clean-restart 完成（空库 + 全新镜像）。容器状态："
& docker compose @composeArgs ps
Show-AppEndpoints
