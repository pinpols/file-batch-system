#!/usr/bin/env bash
# =========================================================
# start-all.sh - 一键启动本地联调环境
# Notes:
# 1) 启动 PostgreSQL / Kafka / MinIO / Redis 以及六个 Java 模块。
# 2) 默认不自动 Maven 打包；如需先构建，请显式传 BUILD=1 或先执行 build-apps.sh。
# 3) 运行前需要 Docker、Docker Compose、JDK；仅在 BUILD=1 时需要 Maven。
# 4) PID 写入 logs/start-all.pids（TAB 分隔：name<TAB>pid<TAB>绝对路径 jar），日志写入 logs/<module>.log。
# 4) 若提示 docker: command not found：安装并启动 Docker Desktop，或保证 docker 在 PATH；
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

LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"
RUNTIME_JAR_DIR="$LOG_DIR/runtime-jars"
mkdir -p "$RUNTIME_JAR_DIR"
PID_FILE="$LOG_DIR/start-all.pids"
PID_FILE_NEW="$(mktemp "$LOG_DIR/start-all.pids.XXXXXX")"
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
  local module="$1"
  local jar
  jar="$(ls "$ROOT/$module/target/${module}"-*-exec.jar 2>/dev/null | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    jar="$(ls "$ROOT/$module/target/${module}"-*.jar 2>/dev/null | grep -Ev 'sources|javadoc|\\.original$|\\-exec\\.jar$' | head -1 || true)"
  fi
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "ERROR: 未找到可执行 jar: $module/target/${module}-*.jar（请先执行 ./scripts/local/build-apps.sh 或 BUILD=1 ./scripts/local/start-all.sh）" >&2
    exit 1
  fi
  printf '%s' "$jar"
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

  local runtime_jar="$RUNTIME_JAR_DIR/${name}.jar"
  cp -f "$jar" "$runtime_jar"

  # Java 25+：Netty 等会调用 System::loadLibrary；显式允许 unnamed 模块原生访问，避免启动期 WARNING
  nohup java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -jar "$runtime_jar" --spring.profiles.active=local >>"$LOG_DIR/${name}.log" 2>&1 &
  local pid=$!
  printf '%s\t%s\t%s\n' "$name" "$pid" "$runtime_jar" >>"$PID_FILE_NEW"
  echo "  已启动 ${name} pid=${pid} 运行包 logs/runtime-jars/${name}.jar 日志 logs/${name}.log"
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
  echo "==> BUILD=1，执行 Maven 打包全部模块（-DskipTests）..."
  mvn -q -DskipTests \
    -pl batch-trigger,batch-orchestrator,batch-worker-import,batch-worker-export,batch-worker-dispatch,batch-console-api \
    -am package -T 1C
else
  echo "==> 跳过 Maven 打包（默认行为）"
  echo "  如需先构建，请执行 ./scripts/local/build-apps.sh"
  echo "  或使用 BUILD=1 ./scripts/local/start-all.sh"
fi

echo "==> 启动 Spring Boot 进程（profile=local）..."
echo "  顺序：orchestrator -> 健康检查 UP -> trigger / console -> 三个 worker"

start_java orchestrator "$(module_jar batch-orchestrator)"
wait_orchestrator_healthy

start_java trigger "$(module_jar batch-trigger)"
start_java console "$(module_jar batch-console-api)"

start_java worker-import "$(module_jar batch-worker-import)"
start_java worker-export "$(module_jar batch-worker-export)"
start_java worker-dispatch "$(module_jar batch-worker-dispatch)"

mv "$PID_FILE_NEW" "$PID_FILE"
trap - EXIT

echo ""
echo "全部进程已在后台运行。端口（默认）："
echo "  console-api 18080 | trigger 18081 | orchestrator 18082 | import 18083 | export 18084 | dispatch 18085"
echo "  Kafka 19092 | MinIO 19000 | Redis 16379（宿主机映射）"
echo "停止请执行: ./scripts/local/stop-all.sh"
