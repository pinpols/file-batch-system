param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\..\..\local\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"
$composeProjectName = Get-EnvValue "COMPOSE_PROJECT_NAME" "batch-local"
$appNetworkName = "${composeProjectName}_batch-network"

& docker network inspect $appNetworkName *> $null
if ($LASTEXITCODE -ne 0) {
  & docker network create $appNetworkName | Out-Null
}

Ensure-Directory (Join-Path $root "logs\docker")

& docker compose `
  --env-file $composeEnvFile `
  -f docker/compose/observability.yml `
  --profile observability `
  up -d @ComposeArgs
exit $LASTEXITCODE
