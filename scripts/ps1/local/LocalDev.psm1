$ErrorActionPreference = "Stop"

function Get-RepoRoot {
  return (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
}

function Import-DotEnv {
  param([string]$Path)
  if (-not (Test-Path $Path)) { return }
  Get-Content $Path | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#") -or $line -notmatch "^\s*([^=\s]+)\s*=\s*(.*)\s*$") { return }
    $name = $Matches[1]
    $value = $Matches[2].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
      $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($name, $value, "Process")
  }
}

function Get-EnvValue {
  param([string]$Name, [string]$Default)
  $value = [Environment]::GetEnvironmentVariable($Name, "Process")
  if ([string]::IsNullOrEmpty($value)) { return $Default }
  return $value
}

function Ensure-Docker {
  if (Get-Command docker -ErrorAction SilentlyContinue) { return }
  $candidates = @(
    "$env:ProgramFiles\Docker\Docker\resources\bin",
    "${env:ProgramFiles(x86)}\Docker\Docker\resources\bin",
    "$env:LOCALAPPDATA\Docker\resources\bin"
  ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "docker.exe")) }
  if ($candidates.Count -gt 0) {
    $env:PATH = "$($candidates[0]);$env:PATH"
    return
  }
  throw "未找到 docker 命令。请安装并启动 Docker Desktop，或把 docker.exe 所在目录加入 PATH。"
}

function Test-DockerDaemon {
  $serverVersion = (& docker version --format "{{.Server.Version}}" 2>$null)
  return ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($serverVersion))
}

function Ensure-DockerDaemon {
  Ensure-Docker
  if (Test-DockerDaemon) { return }
  $context = (& docker context show 2>$null)
  throw "Docker CLI 已安装，但 Docker daemon 当前不可用（context=$context）。请启动 Docker Desktop，等左下角显示 running 后重试；必要时执行 docker context use desktop-linux。"
}

function Ensure-Directory {
  param([string]$Path)
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Get-MavenCommand {
  $mvnd = Get-Command mvnd -ErrorAction SilentlyContinue
  if ($mvnd) { return $mvnd.Source }
  $mvn = Get-Command mvn -ErrorAction SilentlyContinue
  if ($mvn) { return $mvn.Source }
  throw "未找到 Maven。请安装 mvn 或 mvnd，并加入 PATH。"
}

function Enable-MavenNativeAccessForNewJdk {
  $javaVersionText = (& java -version 2>&1 | Out-String)
  if ($javaVersionText -match 'version "([0-9]+)') {
    $major = [int]$Matches[1]
    if ($major -ge 24 -and ($env:MAVEN_OPTS -notmatch '--enable-native-access=ALL-UNNAMED')) {
      $env:MAVEN_OPTS = (($env:MAVEN_OPTS, "--enable-native-access=ALL-UNNAMED") -join " ").Trim()
    }
  }
}

function Split-CommandLine {
  param([string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) { return @() }
  return [regex]::Matches($Value, '("[^"]*"|''[^'']*''|\S+)') | ForEach-Object {
    $_.Value.Trim('"').Trim("'")
  }
}

function Get-AppPort {
  param([string]$Name)
  switch ($Name) {
    "orchestrator" { return (Get-EnvValue "BATCH_ORCHESTRATOR_PORT" "18082") }
    "trigger" { return (Get-EnvValue "BATCH_TRIGGER_PORT" "18081") }
    "console" { return (Get-EnvValue "BATCH_CONSOLE_PORT" "18080") }
    "worker-import" { return (Get-EnvValue "BATCH_WORKER_IMPORT_PORT" "18083") }
    "worker-export" { return (Get-EnvValue "BATCH_WORKER_EXPORT_PORT" "18084") }
    "worker-process" { return (Get-EnvValue "BATCH_WORKER_PROCESS_PORT" "18086") }
    "worker-dispatch" { return (Get-EnvValue "BATCH_WORKER_DISPATCH_PORT" "18085") }
    default { throw "未知模块 '$Name'" }
  }
}

function Get-MavenModuleForApp {
  param([string]$Name)
  switch ($Name) {
    "orchestrator" { return "batch-orchestrator" }
    "trigger" { return "batch-trigger" }
    "console" { return "batch-console-api" }
    "worker-import" { return "batch-worker-import" }
    "worker-export" { return "batch-worker-export" }
    "worker-process" { return "batch-worker-process" }
    "worker-dispatch" { return "batch-worker-dispatch" }
    default { throw "未知模块 '$Name'" }
  }
}

function Get-ProcessIdsByPort {
  param([int]$Port)
  try {
    return @(Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
  } catch {
    return @()
  }
}

function Stop-PortProcess {
  param([string]$Name, [int]$Port)
  $pids = @(Get-ProcessIdsByPort -Port $Port | Where-Object { $_ -and $_ -ne $PID })
  if ($pids.Count -eq 0) {
    Write-Host "  $Name 未运行（端口 $Port 空闲）"
    return
  }
  foreach ($processId in $pids) {
    Write-Host "  停止 $Name（端口 $Port，pid=$processId）"
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
  }
}

function Get-JavaBatchProcesses {
  $runtimeNames = @(
    "orchestrator", "trigger", "console",
    "worker-import", "worker-export", "worker-process", "worker-dispatch"
  )
  Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
    $cmd = $_.CommandLine
    if (-not $cmd) { return $false }
    foreach ($name in $runtimeNames) {
      if ($cmd -like "*build/runtime-jars/$name.jar*" -or $cmd -like "*build\runtime-jars\$name.jar*") { return $true }
    }
    return $false
  }
}

function Wait-ContainerHealthy {
  param([string]$Container, [string]$Label, [int]$Rounds = 90)
  Write-Host "==> 等待 $Label 就绪..."
  for ($i = 1; $i -le $Rounds; $i++) {
    $status = (& docker inspect -f "{{.State.Health.Status}}" $Container 2>$null)
    if ($LASTEXITCODE -eq 0 -and $status -eq "healthy") {
      Write-Host "  $Label 已就绪"
      return
    }
    Start-Sleep -Seconds 2
  }
  & docker logs $Container 2>$null
  throw "$Label 在超时时间内未就绪"
}

function Wait-ContainerExitedZero {
  param([string]$Container, [string]$Label, [int]$Rounds = 60)
  Write-Host "==> 等待 $Label 初始化完成..."
  for ($i = 1; $i -le $Rounds; $i++) {
    $status = (& docker inspect -f "{{.State.Status}}" $Container 2>$null)
    $exitCode = (& docker inspect -f "{{.State.ExitCode}}" $Container 2>$null)
    if ($status -eq "exited" -and $exitCode -eq "0") {
      Write-Host "  $Label 已完成"
      return
    }
    if ($status -eq "exited" -and $exitCode -and $exitCode -ne "0") {
      & docker logs $Container 2>$null
      throw "$Label 初始化失败，exitCode=$exitCode"
    }
    Start-Sleep -Seconds 2
  }
  throw "$Label 在超时时间内未完成"
}

function Wait-HttpUp {
  param([string]$Name, [string]$Url, [int]$Rounds = 90, [int]$IntervalSeconds = 2)
  Write-Host "==> 等待 $Name 健康检查通过（$Url）..."
  for ($i = 1; $i -le $Rounds; $i++) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 8
      if ($response.Content -match '"status"\s*:\s*"UP"') {
        Write-Host "  $Name 已就绪（UP）"
        return
      }
    } catch {}
    Start-Sleep -Seconds $IntervalSeconds
  }
  throw "$Name 在超时时间内未就绪"
}

function Initialize-AppComposeEnv {
  # 全 Docker 模式编排脚本（deploy / restart / clean-restart）的统一环境初始化。
  # 1) 载入 COMPOSE_ENV_FILE（默认 .env.local）到进程环境
  # 2) 对齐时区 / locale（与 start-all 一致的单一配置源）
  # 3) 补齐 docker-compose.app.yml 强制要求(:?required)但 .env(.example) 未定义的变量：
  #    BATCH_PLATFORM_DB_PASSWORD / BATCH_BUSINESS_DB_PASSWORD —— 应用以 postgres 超级
  #      用户(POSTGRES_USER=batch_user)连库，未显式提供时从 POSTGRES_PASSWORD 派生；
  #    BATCH_MINIO_SECRET_KEY / BATCH_MINIO_ACCESS_KEY —— 应用访问 MinIO 用 root 凭据，
  #      未显式提供时从 MINIO_ROOT_PASSWORD / MINIO_ROOT_USER 派生。
  #    否则 docker compose up 会直接报 "... is required" 失败。
  # 4) 默认开启 BuildKit
  # 返回 @{ EnvFile; ProjectName; NetworkName }
  $root = Get-RepoRoot
  $envFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"
  Import-DotEnv (Join-Path $root $envFile)

  $env:BATCH_TIMEZONE_DEFAULT_ZONE = Get-EnvValue "BATCH_TIMEZONE_DEFAULT_ZONE" "Asia/Shanghai"
  $env:TZ = $env:BATCH_TIMEZONE_DEFAULT_ZONE
  $env:BATCH_LOCALE = Get-EnvValue "BATCH_LOCALE" "C.UTF-8"
  $env:LANG = $env:BATCH_LOCALE
  $env:LC_ALL = $env:BATCH_LOCALE

  $pgPass = Get-EnvValue "POSTGRES_PASSWORD" ""
  if ([string]::IsNullOrEmpty((Get-EnvValue "BATCH_PLATFORM_DB_PASSWORD" "")) -and $pgPass) {
    $env:BATCH_PLATFORM_DB_PASSWORD = $pgPass
  }
  if ([string]::IsNullOrEmpty((Get-EnvValue "BATCH_BUSINESS_DB_PASSWORD" "")) -and $pgPass) {
    $env:BATCH_BUSINESS_DB_PASSWORD = $pgPass
  }

  $minioUser = Get-EnvValue "MINIO_ROOT_USER" ""
  $minioPass = Get-EnvValue "MINIO_ROOT_PASSWORD" ""
  if ([string]::IsNullOrEmpty((Get-EnvValue "BATCH_MINIO_SECRET_KEY" "")) -and $minioPass) {
    $env:BATCH_MINIO_SECRET_KEY = $minioPass
  }
  if ([string]::IsNullOrEmpty((Get-EnvValue "BATCH_MINIO_ACCESS_KEY" "")) -and $minioUser) {
    $env:BATCH_MINIO_ACCESS_KEY = $minioUser
  }

  $env:DOCKER_BUILDKIT = Get-EnvValue "DOCKER_BUILDKIT" "1"
  $env:COMPOSE_DOCKER_CLI_BUILD = Get-EnvValue "COMPOSE_DOCKER_CLI_BUILD" "1"

  $projectName = Get-EnvValue "COMPOSE_PROJECT_NAME" "batch-platform"
  return @{
    EnvFile     = $envFile
    ProjectName = $projectName
    NetworkName = "${projectName}_batch-network"
  }
}

function Get-AppComposeArgs {
  # 全 Docker 模式统一的 compose 入参：基础依赖 + 应用 + replica profile。
  # 与 scripts/docker/{build,up,down}-apps.sh 完全一致。
  param([string]$EnvFile)
  return @(
    "--env-file", $EnvFile,
    "-f", "docker-compose.yml",
    "-f", "docker-compose.app.yml",
    "--profile", "apps",
    "--profile", "replica"
  )
}

function Ensure-AppNetwork {
  param([string]$NetworkName)
  & docker network inspect $NetworkName *> $null
  if ($LASTEXITCODE -ne 0) {
    & docker network create $NetworkName | Out-Null
  }
}

function Show-AppEndpoints {
  # 读取实际宿主机映射端口（compose 默认值 ${X:-default} 与此保持一致）；
  # 须在 Initialize-AppComposeEnv 之后调用，以反映 .env 中自定义的端口。
  Write-Host ""
  Write-Host "应用健康检查地址（宿主机，按当前 .env 实际映射端口）："
  Write-Host "  console-api     http://127.0.0.1:$(Get-EnvValue 'CONSOLE_API_PORT' '18080')/actuator/health"
  Write-Host "  trigger         http://127.0.0.1:$(Get-EnvValue 'TRIGGER_PORT' '18081')/actuator/health"
  Write-Host "  orchestrator    http://127.0.0.1:$(Get-EnvValue 'ORCHESTRATOR_PORT' '18082')/actuator/health"
  Write-Host "  worker-import   http://127.0.0.1:$(Get-EnvValue 'WORKER_IMPORT_PORT' '18083')/actuator/health"
  Write-Host "  worker-export   http://127.0.0.1:$(Get-EnvValue 'WORKER_EXPORT_PORT' '18084')/actuator/health"
  Write-Host "  worker-process  http://127.0.0.1:$(Get-EnvValue 'WORKER_PROCESS_PORT' '18086')/actuator/health"
  Write-Host "  worker-dispatch http://127.0.0.1:$(Get-EnvValue 'WORKER_DISPATCH_PORT' '18085')/actuator/health"
  Write-Host "  基础依赖端口以 'docker compose ... ps' 实际输出为准（Postgres/Kafka/MinIO/Redis）。"
}

function Start-JavaApp {
  param(
    [string]$Name,
    [string]$Jar,
    [string]$LogDir,
    [string]$PidFile
  )
  if (-not (Test-Path $Jar)) { throw "未找到 $Jar，请先执行 scripts/ps1/local/build-apps.ps1" }
  $fastOpts = Split-CommandLine (Get-EnvValue "LOCAL_FAST_JVM_OPTS" "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC")
  $javaOpts = Split-CommandLine $env:JAVA_OPTS
  $args = @("--enable-native-access=ALL-UNNAMED") + $fastOpts + $javaOpts + @("-jar", $Jar, "--spring.profiles.active=local")
  $log = Join-Path $LogDir "$Name.log"
  $errLog = Join-Path $LogDir "$Name.err.log"
  $proc = Start-Process -FilePath "java" -ArgumentList $args -RedirectStandardOutput $log -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
  Add-Content -Path $PidFile -Value "$Name`t$($proc.Id)`t$Jar"
  Write-Host "  已启动 $Name pid=$($proc.Id) 运行包 $Jar 日志 $log / $errLog"
}

Export-ModuleMember -Function *
