param()
$ErrorActionPreference = "Continue"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

Write-Host "== PowerShell"
Write-Host "  $($PSVersionTable.PSVersion)"
Write-Host ""

Write-Host "== Java"
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
  Write-Host "  java: $($java.Source)"
  (& java -version 2>&1) | ForEach-Object { Write-Host "  $_" }
} else {
  Write-Host "  未找到 java"
}
Write-Host "  JAVA_HOME: $(Get-EnvValue 'JAVA_HOME' '<未设置>')"
Write-Host ""

Write-Host "== Maven"
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
$mvnd = Get-Command mvnd -ErrorAction SilentlyContinue
if ($mvnd) { Write-Host "  mvnd: $($mvnd.Source)" } else { Write-Host "  mvnd: <未安装>" }
if ($mvn) {
  Write-Host "  mvn:  $($mvn.Source)"
  Enable-MavenNativeAccessForNewJdk
  (& mvn -version 2>&1) | ForEach-Object { Write-Host "  $_" }
} else {
  Write-Host "  mvn: <未安装>"
}
Write-Host "  MAVEN_OPTS: $(Get-EnvValue 'MAVEN_OPTS' '<未设置>')"
Write-Host ""

Write-Host "== Docker"
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
  Write-Host "  docker: $($docker.Source)"
  $context = (& docker context show 2>$null)
  Write-Host "  context: $context"
  & docker compose version
  if (Test-DockerDaemon) {
    $server = (& docker version --format "{{.Server.Version}}" 2>$null)
    Write-Host "  daemon: running, server=$server"
  } else {
    Write-Host "  daemon: 不可用。请启动 Docker Desktop 后重试。"
  }
} else {
  Write-Host "  未找到 docker"
}
