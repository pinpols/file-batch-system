#!/usr/bin/env bash
# =========================================================s s
# start-all.sh - 一键启动本地联调环境
# Notes:
# 1) 启动 PostgreSQL / Kafka / MinIO / Redis 以及六个 Java 模块。
# 2) 默认不自动 Maven 打包；如需先构建，请显式传 BUILD=1 或先执行 build-apps.sh。
# 3) 运行前需要 Docker、Docker Compose、JDK；仅在 BUILD=1 时需要 Maven。
# 4) PID 写入 logs/start-all.pids（TAB 分隔：name<TAB>pid<TAB>绝对路径 jar），日志写入 logs/app/<module>.log。
#    Docker 容器日志通过 docker logs 查看，如需落盘可手动导出到 logs/docker/。
# 5) 每次启动会覆盖模块日志（logs/app/<module>.log），不追加。
# 6) 可执行 jar 统一从 build/runtime-jars/ 读取，由 build-apps.sh 产出。
# 7) 若提示 docker: command not found：安装并启动 Docker Desktop，或保证 docker 在 PATH；
#    本脚本会尝试常见安装路径（Homebrew、Docker.app 等）。
# =========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

_LOCAL_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=docker-path.sh
source "${_LOCAL_SCRIPT_DIR}/docker-path.sh"
ensure_docker_on_path
unset _LOCAL_SCRIPT_DIR

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-local}"
APP_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"

# 本地 dev 启动加速 JVM 参数（6 个模块并发起 Spring Boot fat jar 慢的主因是类扫描+JIT）：
#   TieredStopAtLevel=1  只做 C1 编译，跳过 C2（启动 -30~50%，稳态吞吐 -20~30%，local 无所谓）
#   UseSerialGC          本地负载小，Serial 比 G1 启动开销更低
#   Xverify:none         跳过字节码校验（Java 25 仍可用，会打 deprecation warn 但工作）
# 用户可在外部 export LOCAL_FAST_JVM_OPTS="" 禁用；JAVA_OPTS 追加在后面，同 flag 以后者为准。
LOCAL_FAST_JVM_OPTS="${LOCAL_FAST_JVM_OPTS:--XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xverify:none}"

LOG_ROOT="$ROOT/logs"
DOCKER_LOG_DIR="$LOG_ROOT/docker"
LOG_DIR="$LOG_ROOT/app"
mkdir -p "$LOG_DIR" "$DOCKER_LOG_DIR"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
CDS_DIR="$ROOT/build/cds"
mkdir -p "$RUNTIME_JAR_DIR" "$CDS_DIR"
PID_FILE="$LOG_ROOT/start-all.pids"
PID_FILE_NEW="$(mktemp "$LOG_ROOT/start-all.pids.XXXXXX")"
trap 'rm -f "$PID_FILE_NEW"' EXIT

existing_pid_for() {
  local name="$1"
  if [[ ! -f "$PID_FILE" ]]; then
    return 0
  fi
  # 兼容旧版「空格分隔」与新版 TAB 分隔（字段 1=name，2=pid）
  awk -v name="$name" '
    $1 == name { print $2; exit }
  ' "$PID_FILE"
}

POSTGRES_USER="${POSTGRES_USER:-batch_user}"
POSTGRES_DB="${POSTGRES_DB:-batch_platform}"

if ! docker network inspect "$APP_NETWORK_NAME" >/dev/null 2>&1; then
  docker network create "$APP_NETWORK_NAME" >/dev/null
fi

module_jar() {
  local name="$1"
  local jar="$RUNTIME_JAR_DIR/${name}.jar"
  if [[ ! -f "$jar" ]]; then
    echo "ERROR: 未找到 ${jar}（请先执行 ./scripts/local/build-apps.sh 或 BUILD=1 ./scripts/local/start-all.sh）" >&2
    exit 1
  fi
  printf '%s' "$jar"
}

# AppCDS：首次 training run 生成 .jsa；后续启动 mmap 复用
# 训练期禁用外部依赖（Flyway/Quartz/Kafka listener）减少失败面
# SKIP_CDS=1 可关；jar 新于 .jsa 时自动重训
__CDS_FLAG=""
warm_cds() {
  __CDS_FLAG=""
  local name="$1" jar="$2"
  local archive="$CDS_DIR/${name}.jsa"

  if [[ "${SKIP_CDS:-0}" == "1" ]]; then
    return 0
  fi
  if [[ -f "$archive" && "$jar" -ot "$archive" ]]; then
    __CDS_FLAG="-XX:SharedArchiveFile=$archive"
    return 0
  fi

  echo "  预热 CDS 缓存 ${name}（首次约 15-30s，构建后会重训）..."
  local warm_log="$LOG_DIR/${name}-cds-warmup.log"
  rm -f "$archive"
  java --enable-native-access=ALL-UNNAMED \
    ${LOCAL_FAST_JVM_OPTS} \
    -Dspring.context.exit=onRefresh \
    -Dspring.main.banner-mode=off \
    -Dspring.flyway.enabled=false \
    -Dspring.quartz.auto-startup=false \
    -Dspring.kafka.listener.auto-startup=false \
    -XX:ArchiveClassesAtExit="$archive" \
    -jar "$jar" --spring.profiles.active=local \
    >"$warm_log" 2>&1 &
  local pid=$! elapsed=0
  while kill -0 "$pid" 2>/dev/null; do
    if (( elapsed >= 120 )); then
      kill -9 "$pid" 2>/dev/null
      rm -f "$archive"
      echo "  ⚠  ${name} CDS 预热超时（>120s），跳过 → $warm_log"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  wait "$pid" 2>/dev/null || true

  if [[ -f "$archive" ]]; then
    echo "  ✓ ${name} CDS 缓存就绪 ($(du -h "$archive" 2>/dev/null | cut -f1))"
    __CDS_FLAG="-XX:SharedArchiveFile=$archive"
  else
    echo "  ⚠  ${name} CDS 预热未生成 .jsa，跳过 → $warm_log"
  fi
}

start_java() {
  local name="$1"
  local jar="$2"
  local existing_pid
  existing_pid="$(existing_pid_for "$name")"
  if [[ -n "$existing_pid" ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "  跳过 ${name}（pid=${existing_pid} 仍在运行）"
    printf '%s\t%s\t%s\n' "$name" "$existing_pid" "$RUNTIME_JAR_DIR/${name}.jar" >>"$PID_FILE_NEW"
    return 0
  fi

  warm_cds "$name" "$jar"

  # Java 25+：Netty 等会调用 System::loadLibrary；显式允许 unnamed 模块原生访问，避免启动期 WARNING
  nohup java --enable-native-access=ALL-UNNAMED ${LOCAL_FAST_JVM_OPTS} ${__CDS_FLAG} ${JAVA_OPTS:-} -jar "$jar" --spring.profiles.active=local >"$LOG_DIR/${name}.log" 2>&1 &
  local pid=$!
  printf '%s\t%s\t%s\n' "$name" "$pid" "$jar" >>"$PID_FILE_NEW"
  echo "  已启动 ${name} pid=${pid} 运行包 build/runtime-jars/${name}.jar 日志 logs/app/${name}.log"
}

wait_postgres() {
  echo "==> 等待 PostgreSQL 就绪..."
  local i
  for i in $(seq 1 90); do
    if docker compose --env-file "$COMPOSE_ENV_FILE" exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
      echo "  PostgreSQL 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "ERROR: PostgreSQL 在超时时间内未就绪" >&2
  exit 1
}

wait_container_exited_zero() {
  local container="$1"
  local label="$2"
  echo "==> 等待 ${label} 初始化完成..."
  local i status exit_code
  for i in $(seq 1 60); do
    status="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
    exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "exited" && "$exit_code" == "0" ]]; then
      echo "  ${label} 已完成"
      return 0
    fi
    if [[ "$status" == "exited" && "$exit_code" != "" && "$exit_code" != "0" ]]; then
      echo "ERROR: ${label} 初始化失败，exitCode=${exit_code}" >&2
      docker logs "$container" >&2 || true
      exit 1
    fi
    sleep 2
  done
  echo "ERROR: ${label} 在超时时间内未完成" >&2
  exit 1
}

wait_kafka_topics_ready() {
  echo "==> 等待 Kafka topic 初始化完成..."
  local expected_topics="${KAFKA_TOPICS:-batch.task.dispatch.import,batch.task.dispatch.export,batch.task.dispatch.dispatch,batch.task.result,batch.task.retry,batch.task.dead-letter}"
  local i all_ready listed
  for i in $(seq 1 60); do
    listed="$(docker exec batch-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list 2>/dev/null || true)"
    all_ready=true
    local old_ifs="$IFS"
    IFS=','
    for raw_topic in $expected_topics; do
      local topic
      topic="$(echo "${raw_topic}" | tr -d '[:space:]')"
      [[ -n "$topic" ]] || continue
      if ! grep -Fxq "$topic" <<<"$listed"; then
        all_ready=false
        break
      fi
    done
    IFS="$old_ifs"
    if [[ "$all_ready" == "true" ]]; then
      echo "  Kafka topics 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "ERROR: Kafka topics 在超时时间内未就绪" >&2
  docker logs batch-kafka-init >&2 || true
  exit 1
}

wait_container_healthy() {
  local container="$1"
  local label="$2"
  echo "==> 等待 ${label} 就绪..."
  local i status
  for i in $(seq 1 90); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
      echo "  ${label} 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "ERROR: ${label} 在超时时间内未就绪" >&2
  docker logs "$container" >&2 || true
  exit 1
}

# Orchestrator 就绪后再启动三个 worker（与 stop-all 顺序对称）
wait_orchestrator_healthy() {
  local port="${BATCH_ORCHESTRATOR_PORT:-18082}"
  local url="http://127.0.0.1:${port}/actuator/health"
  local rounds="${ORCH_HEALTH_WAIT_ROUNDS:-90}"
  local interval="${ORCH_HEALTH_INTERVAL_SEC:-2}"
  echo "==> 等待 Orchestrator 健康检查通过（${url}），通过后再启动 worker..."
  sleep "${START_ORCHESTRATOR_PAUSE_SEC:-2}"
  if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: 需要 curl 以探测 Orchestrator 健康检查" >&2
    exit 1
  fi
  local i
  for i in $(seq 1 "$rounds"); do
    if curl -sf --connect-timeout 2 --max-time 8 "$url" 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      echo "  Orchestrator 已就绪（UP）"
      return 0
    fi
    sleep "$interval"
  done
  echo "ERROR: Orchestrator 在超时时间内未就绪，请查看 logs/orchestrator.log" >&2
  exit 1
}



echo "==> Docker Compose 启动基础依赖（postgres / kafka / minio / redis）..."
docker compose --env-file "$COMPOSE_ENV_FILE" up -d
wait_postgres
wait_container_healthy batch-minio "MinIO"
wait_container_healthy batch-redis "Redis"
wait_kafka_topics_ready
wait_container_exited_zero batch-minio-init "MinIO bucket init"

if [[ "${BUILD:-0}" == "1" ]]; then
  "$ROOT/scripts/local/build-apps.sh"
else
  echo "==> 跳过 Maven 打包（默认行为）"
  echo "  如需先构建，请执行 ./scripts/local/build-apps.sh"
  echo "  或使用 BUILD=1 ./scripts/local/start-all.sh"
fi

# ── 启动前端口占用检查：有残留进程则清理，避免 Address already in use ──
_clear_occupied_ports() {
  local names=(
    "orchestrator"
    "trigger"
    "console"
    "worker-import"
    "worker-export"
    "worker-dispatch"
  )
  local ports=(
    "${BATCH_ORCHESTRATOR_PORT:-18082}"
    "${BATCH_TRIGGER_PORT:-18081}"
    "${BATCH_CONSOLE_PORT:-18080}"
    "${BATCH_WORKER_IMPORT_PORT:-18083}"
    "${BATCH_WORKER_EXPORT_PORT:-18084}"
    "${BATCH_WORKER_DISPATCH_PORT:-18085}"
  )
  local found=0
  local i
  for ((i = 0; i < ${#names[@]}; i++)); do
    local name="${names[$i]}"
    local port="${ports[$i]}"
    local pids
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
    [[ -z "$pids" ]] && continue
    echo "  端口 ${port} (${name}) 被占用，清理残留进程..."
    while IFS= read -r pid; do
      [[ -z "$pid" ]] && continue
      echo "    kill -9 pid=${pid}"
      kill -9 "$pid" 2>/dev/null || true
      ((found += 1)) || true
    done <<<"$pids"
  done
  if (( found > 0 )); then
    echo "  已清理 ${found} 个残留进程，等待端口释放..."
    sleep 2
  fi
}

echo "==> 检查应用端口占用..."
_clear_occupied_ports

# ── 轻量启动开关 ───────────────────────────────────────────
# START_CONSOLE=0   跳过 console-api（不需要前端 BFF 时）
# START_TRIGGER=0   跳过 trigger（不需要定时触发器时）
# START_WORKERS=0   跳过全部 worker（只调 orchestrator / console 接口时）
# WORKERS=import,export   只启动指定 worker，逗号分隔（默认全启）
START_CONSOLE="${START_CONSOLE:-1}"
START_TRIGGER="${START_TRIGGER:-1}"
START_WORKERS="${START_WORKERS:-1}"
WORKERS="${WORKERS:-import,export,dispatch}"

echo "==> 启动 Spring Boot 进程（profile=local）..."
echo "  顺序：orchestrator -> 健康检查 UP -> trigger / console -> worker(s)"
echo "  START_CONSOLE=${START_CONSOLE}  START_TRIGGER=${START_TRIGGER}  START_WORKERS=${START_WORKERS}  WORKERS=${WORKERS}"

start_java orchestrator "$(module_jar orchestrator)"
wait_orchestrator_healthy

[[ "${START_TRIGGER}" == "1" ]] && start_java trigger "$(module_jar trigger)"
[[ "${START_CONSOLE}" == "1" ]] && start_java console "$(module_jar console)"

if [[ "${START_WORKERS}" == "1" ]]; then
  # 把 WORKERS 字符串（逗号分隔）切成数组，避免在循环期间改 IFS 影响 start_java 内部
  # 对 ${LOCAL_FAST_JVM_OPTS} 等空格分隔参数的 word-splitting
  IFS=',' read -ra _WORKERS_ARR <<<"$WORKERS"
  for w in "${_WORKERS_ARR[@]}"; do
    w="${w// /}"   # trim spaces
    [[ -n "$w" ]] && start_java "worker-${w}" "$(module_jar "worker-${w}")"
  done
  unset _WORKERS_ARR
fi

mv "$PID_FILE_NEW" "$PID_FILE"
trap - EXIT

echo ""
echo "全部进程已在后台运行。端口（默认）："
echo "  console-api 18080 | trigger 18081 | orchestrator 18082 | import 18083 | export 18084 | dispatch 18085"
echo "  Kafka 19092 | MinIO 19000 | Redis 16379（宿主机映射）"
echo "停止请执行: ./scripts/local/stop-all.sh"

# ─────────────────────────────────────────────
# 健康检查：等待所有 Spring Boot 应用均 UP
# 总超时 3 分钟；每 5 秒轮询一次；已 UP 的不再重复探测
# ─────────────────────────────────────────────
_port_for_app() {
  case "$1" in
    orchestrator)   echo "${BATCH_ORCHESTRATOR_PORT:-18082}" ;;
    trigger)        echo "${BATCH_TRIGGER_PORT:-18081}" ;;
    console)        echo "${BATCH_CONSOLE_PORT:-18080}" ;;
    worker-import)  echo "${BATCH_WORKER_IMPORT_PORT:-18083}" ;;
    worker-export)  echo "${BATCH_WORKER_EXPORT_PORT:-18084}" ;;
    worker-dispatch) echo "${BATCH_WORKER_DISPATCH_PORT:-18085}" ;;
  esac
}

_mark_up() {   # _mark_up "name" → appends to $up_list
  up_list="${up_list} $1 "
}

_is_up() {    # _is_up "name" → true if already marked
  [[ "$up_list" == *" $1 "* ]]
}

wait_all_apps_healthy() {
  # 兼容 Bash 3.2（macOS 系统 bash 不支持 local -A 关联数组）
  # $1: 空格分隔的待检模块列表（由调用方按实际启动的模块传入）
  local check_list="$1"
  local timeout="${APP_HEALTH_WAIT_SEC:-300}"
  local interval=5
  local deadline=$(( $(date +%s) + timeout ))
  # 空格包围，用于 _is_up 字符串匹配；orchestrator 已由 wait_orchestrator_healthy 确认
  local up_list=" orchestrator "

  echo ""
  echo "==> 健康检查：等待所有 Spring Boot 应用 UP（最多 ${timeout}s，可通过 APP_HEALTH_WAIT_SEC 覆盖）..."

  while true; do
    local now
    now=$(date +%s)
    if (( now >= deadline )); then
      break
    fi

    local all_up=true
    local name port url
    for name in $check_list; do
      _is_up "$name" && continue   # 已 UP，跳过
      port="$(_port_for_app "$name")"
      url="http://127.0.0.1:${port}/actuator/health"
      if curl -sf --connect-timeout 2 --max-time 5 "$url" 2>/dev/null \
          | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        _mark_up "$name"
        printf '  ✓ %-18s UP  (port %s)\n' "$name" "$port"
      else
        all_up=false
      fi
    done

    if [[ "$all_up" == "true" ]]; then
      echo ""
      echo "  所有应用均已 UP，本地环境启动完成。"
      return 0
    fi

    local remaining=$(( deadline - $(date +%s) ))
    (( remaining > 0 )) && sleep $interval
  done

  # 超时后打印汇总
  echo ""
  echo "  ┌──────────────────── 健康检查结果（超时 ${timeout}s）────────────────────┐"
  local name port
  for name in $check_list; do
    port="$(_port_for_app "$name")"
    if _is_up "$name"; then
      printf '  │  ✓ %-18s UP      (port %s)\n' "$name" "$port"
    else
      printf '  │  ✗ %-18s 未就绪  (port %s)  → 查看 logs/app/%s.log\n' "$name" "$port" "$name"
    fi
  done
  echo "  └──────────────────────────────────────────────────────────────────────┘"
  echo "WARNING: 部分应用未在超时时间内就绪，请检查上方日志路径。" >&2
  return 1
}

# 根据实际启动的模块构建待检列表
_build_check_list() {
  local list="orchestrator"
  [[ "${START_TRIGGER}" == "1" ]] && list="$list trigger"
  [[ "${START_CONSOLE}" == "1" ]] && list="$list console"
  if [[ "${START_WORKERS}" == "1" ]]; then
    local w
    local saved_ifs="$IFS"
    IFS=','
    for w in $WORKERS; do
      w="${w// /}"
      [[ -n "$w" ]] && list="$list worker-${w}"
    done
    IFS="$saved_ifs"
  fi
  echo "$list"
}

wait_all_apps_healthy "$(_build_check_list)" || true
