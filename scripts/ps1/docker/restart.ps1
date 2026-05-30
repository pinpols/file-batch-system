<#
.SYNOPSIS
  全 Docker 模式：增量重启后端（重建镜像[走缓存] + 重新创建应用容器；保留数据卷）。

.DESCRIPTION
  Windows / PowerShell 版 restart，等价于 `make dev-restart` 的 Docker 版语义：
    停应用 -> 增量重建镜像（docker layer 缓存）-> up --force-recreate 重新创建容器。
  基础依赖（PG/Kafka/MinIO/Redis）与所有数据卷保持不动，数据不丢失。

  覆盖 95% 日常改代码后的迭代。若怀疑构建缓存脏 / 删改类残留 / 需清库，
  改用 clean-restart.ps1。

.PARAMETER NoBuild
  跳过镜像重建，只重新创建容器（纯重启已有镜像）。

.PARAMETER SkipWait
  up 后不等待健康检查（默认 --wait 直到全部 healthy，超时 300s）。

.PARAMETER Services
  仅重启指定 compose 服务（透传给 build/up），留空表示全部应用。例如：console-api

.EXAMPLE
  .\scripts\ps1\docker\restart.ps1

.EXAMPLE
  .\scripts\ps1\docker\restart.ps1 console-api          # 只重建并重启 console-api

.EXAMPLE
  .\scripts\ps1\docker\restart.ps1 -NoBuild             # 不重编，仅重启容器
#>
param(
  [switch]$NoBuild,
  [switch]$SkipWait,
  [Parameter(ValueFromRemainingArguments = $true)][string[]]$Services
)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\..\local\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$ctx = Initialize-AppComposeEnv
$composeArgs = Get-AppComposeArgs -EnvFile $ctx.EnvFile
Ensure-AppNetwork -NetworkName $ctx.NetworkName
Ensure-Directory (Join-Path $root "logs\docker")

$scope = if ($Services -and $Services.Count -gt 0) { $Services -join ", " } else { "全部应用" }
Write-Host "==> 全 Docker 模式增量重启（env=$($ctx.EnvFile) 目标=$scope；保留数据卷）"

if (-not $NoBuild) {
  Write-Host "==> [1/2] 增量重建应用镜像（走 Docker layer 缓存）..."
  & docker compose @composeArgs build @Services
  if ($LASTEXITCODE -ne 0) { throw "镜像构建失败（exit=$LASTEXITCODE）" }
} else {
  Write-Host "==> [1/2] 跳过镜像重建（-NoBuild）"
}

Write-Host "==> [2/2] 重新创建容器（--force-recreate；依赖与数据卷不动）..."
$upArgs = @("up", "-d", "--force-recreate", "--remove-orphans")
if (-not $SkipWait) { $upArgs += @("--wait", "--wait-timeout", "300") }
& docker compose @composeArgs @upArgs @Services
$upExit = $LASTEXITCODE
if ($upExit -ne 0) {
  Write-Warning "compose up 返回非 0（exit=$upExit）。当前状态如下，便于排查："
  & docker compose @composeArgs ps
  throw "重启未完全就绪（exit=$upExit）。排查：docker compose $($composeArgs -join ' ') logs <service>，或 logs/docker/*.log"
}

Write-Host ""
Write-Host "==> 重启完成。容器状态："
& docker compose @composeArgs ps
Show-AppEndpoints
