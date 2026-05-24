param()
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\..\..\local\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"

& docker compose `
  --env-file $composeEnvFile `
  -f docker/compose/observability.yml `
  --profile observability `
  stop
exit $LASTEXITCODE
