#!/usr/bin/env bash
# 一键启动：Docker 基础依赖（PostgreSQL / Kafka / MinIO）+ 六个可运行 Java 模块。
# 依赖：Docker、Docker Compose、JDK、Maven；在仓库根目录执行。
# 日志：logs/<模块名>.log；PID：logs/start-all.pids（name pid 两列）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"
PID_FILE="$LOG_DIR/start-all.pids"
PID_FILE_NEW="$(mktemp "$LOG_DIR/start-all.pids.XXXXXX")"
trap 'rm -f "$PID_FILE_NEW"' EXIT

existing_pid_for() {
  local name="$1"
  if [[ ! -f "$PID_FILE" ]]; then
    return 0
  fi
  awk -v name="$name" '$1 == name { print $2; exit }' "$PID_FILE"
}

POSTGRES_USER="${POSTGRES_USER:-batch_user}"
POSTGRES_DB="${POSTGRES_DB:-batch_platform}"

module_jar() {
  local module="$1"
  local jar
  jar="$(ls "$ROOT/$module/target"/"${module}"-*.jar 2>/dev/null | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "ERROR: 未找到可执行 jar: $module/target/${module}-*.jar（请先成功执行 mvn package）" >&2
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
    echo "${name} ${existing_pid}" >>"$PID_FILE_NEW"
    return 0
  fi

  nohup java ${JAVA_OPTS:-} -jar "$jar" --spring.profiles.active=local >>"$LOG_DIR/${name}.log" 2>&1 &
  local pid=$!
  echo "${name} ${pid}" >>"$PID_FILE_NEW"
  echo "  已启动 ${name} pid=${pid} 日志 logs/${name}.log"
}

wait_postgres() {
  echo "==> 等待 PostgreSQL 就绪..."
  local i
  for i in $(seq 1 90); do
    if docker compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
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

echo "==> Docker Compose 启动基础依赖（postgres / kafka / minio）..."
docker compose up -d
wait_postgres
wait_container_healthy batch-minio "MinIO"
wait_container_exited_zero batch-kafka-init "Kafka topic init"
wait_container_exited_zero batch-minio-init "MinIO bucket init"

echo "==> Maven 打包全部模块（-DskipTests）..."
mvn -q -DskipTests package -T 1C

echo "==> 启动 Spring Boot 进程（profile=local）..."
echo "  顺序：orchestrator -> 短暂等待 -> trigger / console / 三个 worker"

start_java orchestrator "$(module_jar batch-orchestrator)"
sleep "${START_ORCHESTRATOR_PAUSE_SEC:-8}"

start_java trigger "$(module_jar batch-trigger)"
start_java console "$(module_jar batch-console-api)"

start_java worker-import "$(module_jar batch-worker-import)"
start_java worker-export "$(module_jar batch-worker-export)"
start_java worker-dispatch "$(module_jar batch-worker-dispatch)"

mv "$PID_FILE_NEW" "$PID_FILE"
trap - EXIT

echo ""
echo "全部进程已在后台运行。端口（默认）："
echo "  console-api 8080 | trigger 8081 | orchestrator 8082 | import 8083 | export 8084 | dispatch 8085"
echo "停止请执行: ./scripts/local/stop-all.sh"
