param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\..\local\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"
$env:DOCKER_BUILDKIT = Get-EnvValue "DOCKER_BUILDKIT" "1"
$env:COMPOSE_DOCKER_CLI_BUILD = Get-EnvValue "COMPOSE_DOCKER_CLI_BUILD" "1"

& docker compose `
  --env-file $composeEnvFile `
  -f docker-compose.yml `
  -f docker-compose.app.yml `
  --profile apps `
  --profile replica `
  build @ComposeArgs
exit $LASTEXITCODE
