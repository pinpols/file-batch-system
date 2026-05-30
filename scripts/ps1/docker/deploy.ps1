<#
.SYNOPSIS
  全 Docker 模式：一键部署后端（构建镜像 + 拉起基础依赖与应用容器 + 等待健康）。

.DESCRIPTION
  Windows / PowerShell 版「部署基础环境」入口，等价于 sh 工作流
  scripts/docker/build-apps.sh + up-apps.sh，并补齐健康等待与访问信息输出。

  执行步骤：
    1) 校验 Docker daemon
    2) docker compose build      构建 7 个应用镜像（BuildKit，走层缓存）
    3) docker compose up -d --wait  拉起 PG/Kafka/MinIO/Redis(+replica) 与全部 apps，
       并等待所有容器健康检查通过

  默认使用 .env.local（用 $env:COMPOSE_ENV_FILE 覆盖，例如 .env.test）。
  基础依赖与应用库口令见 LocalDev.psm1 Initialize-AppComposeEnv 注释。

.PARAMETER NoBuild
  跳过镜像构建，直接用已有镜像拉起。

.PARAMETER SkipWait
  up 后不等待健康检查（默认 --wait 直到全部 healthy，超时 300s）。

.PARAMETER Services
  仅针对指定 compose 服务（透传给 build/up），留空表示全部。例如：console-api

.EXAMPLE
  .\scripts\ps1\docker\deploy.ps1

.EXAMPLE
  .\scripts\ps1\docker\deploy.ps1 -NoBuild

.EXAMPLE
  $env:COMPOSE_ENV_FILE='.env.test'; .\scripts\ps1\docker\deploy.ps1
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

Write-Host "==> 全 Docker 模式部署后端（env=$($ctx.EnvFile) project=$($ctx.ProjectName)）"

if (-not $NoBuild) {
  Write-Host "==> [1/2] 构建应用镜像（docker compose build；首次较慢，镜像内 mvn package）..."
  & docker compose @composeArgs build @Services
  if ($LASTEXITCODE -ne 0) { throw "镜像构建失败（exit=$LASTEXITCODE）" }
} else {
  Write-Host "==> [1/2] 跳过镜像构建（-NoBuild）"
}

Write-Host "==> [2/2] 拉起基础依赖 + 应用容器..."
$upArgs = @("up", "-d", "--remove-orphans")
if (-not $SkipWait) { $upArgs += @("--wait", "--wait-timeout", "300") }
& docker compose @composeArgs @upArgs @Services
$upExit = $LASTEXITCODE
if ($upExit -ne 0) {
  Write-Warning "compose up 返回非 0（exit=$upExit）。当前状态如下，便于排查："
  & docker compose @composeArgs ps
  throw "部署未完全就绪（exit=$upExit）。排查：docker compose $($composeArgs -join ' ') logs <service>，或 logs/docker/*.log"
}

Write-Host ""
Write-Host "==> 部署完成。容器状态："
& docker compose @composeArgs ps
Show-AppEndpoints
Write-Host ""
Write-Host "后续：增量重启 .\scripts\ps1\docker\restart.ps1 ；彻底重置 .\scripts\ps1\docker\clean-restart.ps1"
