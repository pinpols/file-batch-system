param()
$ErrorActionPreference = "Stop"
Import-Module "$PSScriptRoot\LocalDev.psm1" -Force -DisableNameChecking

$root = Get-RepoRoot
Set-Location $root
Ensure-DockerDaemon

$composeEnvFile = Get-EnvValue "COMPOSE_ENV_FILE" ".env.local"
$composeProjectName = Get-EnvValue "COMPOSE_PROJECT_NAME" "batch-platform"
$appNetworkName = "${composeProjectName}_batch-network"

Import-DotEnv (Join-Path $root $composeEnvFile)
$env:BATCH_TIMEZONE_DEFAULT_ZONE = Get-EnvValue "BATCH_TIMEZONE_DEFAULT_ZONE" "Asia/Shanghai"
$env:TZ = $env:BATCH_TIMEZONE_DEFAULT_ZONE
$env:BATCH_LOCALE = Get-EnvValue "BATCH_LOCALE" "C.UTF-8"
$env:LANG = $env:BATCH_LOCALE
$env:LC_ALL = $env:BATCH_LOCALE
$env:BATCH_CONSOLE_READ_REPLICA_ENABLED = Get-EnvValue "BATCH_CONSOLE_READ_REPLICA_ENABLED" "true"

$logRoot = Join-Path $root "logs"
$logDir = Join-Path $logRoot "app"
$dockerLogDir = Join-Path $logRoot "docker"
$runtimeJarDir = Join-Path $root "build\runtime-jars"
$pidFile = Join-Path $logRoot "start-all.pids"
$pidNew = Join-Path $logRoot "start-all.pids.$([Guid]::NewGuid().ToString('N'))"
Ensure-Directory $logDir
Ensure-Directory $dockerLogDir
Ensure-Directory $runtimeJarDir
New-Item -ItemType File -Force -Path $pidNew | Out-Null

& docker network inspect $appNetworkName *> $null
if ($LASTEXITCODE -ne 0) {
  & docker network create $appNetworkName | Out-Null
}

$profiles = @()
if ((Get-EnvValue "BATCH_CONSOLE_READ_REPLICA_ENABLED" "true") -eq "true") {
  $profiles = @("--profile", "replica")
  Write-Host "==> read-replica 已启用 -> 同时启动 postgres-replica（流复制 hot standby）"
} else {
  Write-Host "==> read-replica 已显式关闭 -> 跳过 postgres-replica 容器"
}

Write-Host "==> Docker Compose 启动基础依赖..."
& docker compose --env-file $composeEnvFile @profiles up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> 等待基础服务就绪..."
$postgresUser = Get-EnvValue "POSTGRES_USER" "batch_user"
$postgresDb = Get-EnvValue "POSTGRES_DB" "batch_platform"
for ($i = 1; $i -le 90; $i++) {
  & docker compose --env-file $composeEnvFile exec -T postgres-primary pg_isready -U $postgresUser -d $postgresDb *> $null
  if ($LASTEXITCODE -eq 0) { Write-Host "  PostgreSQL 已就绪"; break }
  if ($i -eq 90) { throw "PostgreSQL 在超时时间内未就绪" }
  Start-Sleep -Seconds 2
}
Wait-ContainerHealthy batch-minio "MinIO"
Wait-ContainerHealthy batch-valkey "Redis"
if ((Get-EnvValue "BATCH_CONSOLE_READ_REPLICA_ENABLED" "true") -eq "true") {
  Wait-ContainerHealthy batch-postgres-replica "PG Replica"
}
Wait-ContainerExitedZero batch-minio-init "MinIO bucket init"

$ddl = Join-Path $root "scripts\db\business\create_biz_tables.sql"
if (Test-Path $ddl) {
  Write-Host "==> 应用业务库 DDL（biz.* + batch.process_staging）..."
  Get-Content $ddl -Raw | & docker exec -i batch-postgres-primary psql -U $postgresUser -d batch_business -v ON_ERROR_STOP=1 *> $null
  if ($LASTEXITCODE -eq 0) { Write-Host "  业务库 DDL 已 apply" } else { Write-Host "  WARNING: 业务库 DDL apply 失败（不阻塞启动）" }
}

if ((Get-EnvValue "BUILD" "0") -eq "1") {
  & "$PSScriptRoot\build-apps.ps1"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
  Write-Host "==> 跳过 Maven 打包（默认行为）"
  Write-Host "  如需先构建，请执行 .\scripts\ps1\local\build-apps.ps1"
  Write-Host "  或使用 `$env:BUILD='1'; .\scripts\ps1\local\start-all.ps1"
}

Write-Host "==> 检查应用端口占用..."
@("orchestrator", "trigger", "console", "worker-import", "worker-export", "worker-process", "worker-dispatch", "worker-atomic") | ForEach-Object {
  $port = [int](Get-AppPort $_)
  $pids = @(Get-ProcessIdsByPort -Port $port | Where-Object { $_ -and $_ -ne $PID })
  foreach ($processId in $pids) {
    Write-Host "  端口 $port ($_) 被占用，清理 pid=$processId"
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
  }
}

$startConsole = Get-EnvValue "START_CONSOLE" "1"
$startTrigger = Get-EnvValue "START_TRIGGER" "1"
$startWorkers = Get-EnvValue "START_WORKERS" "1"
$workers = Get-EnvValue "WORKERS" "import,export,process,dispatch"

Write-Host "==> 启动 Spring Boot 进程（profile=local）..."
Write-Host "  顺序：orchestrator -> 健康检查 UP -> trigger / console -> worker(s)"
Write-Host "  START_CONSOLE=$startConsole  START_TRIGGER=$startTrigger  START_WORKERS=$startWorkers  WORKERS=$workers"

Start-JavaApp -Name "orchestrator" -Jar (Join-Path $runtimeJarDir "orchestrator.jar") -LogDir $logDir -PidFile $pidNew
$orchPort = Get-AppPort "orchestrator"
Wait-HttpUp -Name "Orchestrator" -Url "http://127.0.0.1:$orchPort/actuator/health" -Rounds ([int](Get-EnvValue "ORCH_HEALTH_WAIT_ROUNDS" "90")) -IntervalSeconds ([int](Get-EnvValue "ORCH_HEALTH_INTERVAL_SEC" "2"))

if ($startTrigger -eq "1") { Start-JavaApp -Name "trigger" -Jar (Join-Path $runtimeJarDir "trigger.jar") -LogDir $logDir -PidFile $pidNew }
if ($startConsole -eq "1") { Start-JavaApp -Name "console" -Jar (Join-Path $runtimeJarDir "console.jar") -LogDir $logDir -PidFile $pidNew }
if ($startWorkers -eq "1") {
  $workers.Split(",") | ForEach-Object {
    $w = $_.Trim()
    if ($w) {
      $name = "worker-$w"
      Start-JavaApp -Name $name -Jar (Join-Path $runtimeJarDir "$name.jar") -LogDir $logDir -PidFile $pidNew
    }
  }
}

Move-Item $pidNew $pidFile -Force

Write-Host ""
Write-Host "全部进程已在后台运行。端口（默认）："
Write-Host "  console-api 18080 | trigger 18081 | orchestrator 18082 | import 18083 | export 18084 | process 18086 | dispatch 18085 | atomic 18087"
Write-Host "  Postgres 15432 | Kafka 19092 | MinIO 19000 | Redis 16379（宿主机映射）"
Write-Host "停止请执行: .\scripts\ps1\local\stop-all.ps1"

$checkList = @("orchestrator")
if ($startTrigger -eq "1") { $checkList += "trigger" }
if ($startConsole -eq "1") { $checkList += "console" }
if ($startWorkers -eq "1") { $checkList += ($workers.Split(",") | ForEach-Object { "worker-$($_.Trim())" }) }

Write-Host ""
Write-Host "==> 健康检查：等待所有 Spring Boot 应用 UP..."
foreach ($name in $checkList) {
  $port = Get-AppPort $name
  try {
    Wait-HttpUp -Name $name -Url "http://127.0.0.1:$port/actuator/health" -Rounds 60 -IntervalSeconds 5
  } catch {
    Write-Warning "$name 未在超时时间内就绪，查看 logs/app/$name.log"
  }
}
