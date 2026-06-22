#!/usr/bin/env bash
# =========================================================s s
# start-all.sh - 一键启动本地联调环境
# 说明：
# 1) 启动 PostgreSQL / Kafka / MinIO / Redis 以及六个 Java 模块。
# 2) 默认不自动 Maven 打包；如需先构建，请显式传 BUILD=1 或先执行 build-apps.sh。
# 3) 运行前需要 Docker、Docker Compose、JDK；仅在 BUILD=1 时需要 Maven。
# 4) PID 写入 logs/pids/start-all.pids（兼容软链 logs/start-all.pids），日志写入 logs/current/app/<module>.log。
#    Docker 容器日志通过 docker logs 查看，如需落盘可手动导出到 logs/docker/。
# 5) 每次启动会覆盖模块当前日志（兼容软链 logs/app/<module>.log），不追加。
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
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-batch-platform}"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
# shellcheck source=../lib/process.sh
source "$ROOT/scripts/lib/process.sh"
APP_NETWORK_NAME="${COMPOSE_PROJECT_NAME}_batch-network"
PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
PG_REPLICA_CONTAINER="${PG_REPLICA_CONTAINER:-batch-postgres-replica}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-batch-kafka}"
KAFKA_INIT_CONTAINER="${KAFKA_INIT_CONTAINER:-batch-kafka-init}"
MINIO_CONTAINER="${MINIO_CONTAINER:-batch-minio}"
MINIO_INIT_CONTAINER="${MINIO_INIT_CONTAINER:-batch-minio-init}"
REDIS_CONTAINER="${REDIS_CONTAINER:-batch-valkey}"

# 本地 dev 启动加速 JVM 参数（6 个模块并发起 Spring Boot fat jar 慢的主因是类扫描+JIT）：
#   TieredStopAtLevel=1  只做 C1 编译，跳过 C2（启动 -30~50%，稳态吞吐 -20~30%，local 无所谓）
#   UseSerialGC          本地负载小，Serial 比 G1 启动开销更低
# （JDK 13+ 已弃用 -Xverify:none / -noverify，默认不再注入，避免告警）
# 用户可在外部 export LOCAL_FAST_JVM_OPTS="" 禁用；JAVA_OPTS 追加在后面，同 flag 以后者为准。
LOCAL_FAST_JVM_OPTS="${LOCAL_FAST_JVM_OPTS:--XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off}"

# AppCDS：JDK 25 + Spring Boot 4 下 dump/runtime 的 jdk.module.enable.native.access 状态会错位，
# 导致 MyBatis ExceptionUtil / Spring MVC PartialMatchHelper / Tomcat RequestUtil 等内部类
# 运行时 NoClassDefFoundError。统一关 CDS 规避；待 JDK / Spring Boot 修复后再恢复。
SKIP_CDS="${SKIP_CDS:-1}"
CDS_ARCHIVE_STAMP="${CDS_ARCHIVE_STAMP:-v3-share-off}"

LOG_ROOT="$ROOT/logs"
LOG_DIR="$(log_current_dir "$ROOT" app app)"
DOCKER_LOG_DIR="$(log_current_dir "$ROOT" docker docker)"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
CDS_DIR="$ROOT/build/cds"
mkdir -p "$RUNTIME_JAR_DIR" "$CDS_DIR"
PID_FILE="$(log_pid_file "$ROOT" start-all.pids)"
PID_FILE_NEW="$(mktemp "$PID_FILE.XXXXXX")"
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
# SKIP_CDS=1 可关；jar 内容 hash / 路径 / mtime / size 或 JVM 指纹变化时自动重训。
__CDS_FLAG=""
cds_metadata() {
  local jar="$1"
  local jar_hash jar_stat
  jar_hash="$(shasum -a 256 "$jar" 2>/dev/null | awk '{print $1}')"
  jar_stat="$(stat -f '%N:%z:%m' "$jar" 2>/dev/null || stat -c '%n:%s:%Y' "$jar" 2>/dev/null || true)"
  printf 'jar_sha256=%s\njar_stat=%s\ncds_stamp=%s\nlocal_fast_jvm_opts=%s\njava_opts=%s\n' \
    "$jar_hash" "$jar_stat" "$CDS_ARCHIVE_STAMP" "$LOCAL_FAST_JVM_OPTS" "${JAVA_OPTS:-}"
}

warm_cds() {
  __CDS_FLAG=""
  local name="$1" jar="$2"
  local archive="$CDS_DIR/${name}.jsa"
  local hash_file="$archive.sha256"

  if [[ "${SKIP_CDS:-0}" == "1" ]]; then
    return 0
  fi

  local expected_meta
  expected_meta="$(cds_metadata "$jar")"
  # archive 存在且 jar 指纹 + jar 元数据 + CDS 训练 JVM 指纹一致 —— 直接用
  if [[ -f "$archive" && -f "$hash_file" && -n "$expected_meta" \
        && "$(cat "$hash_file" 2>/dev/null)" == "$expected_meta" ]]; then
    __CDS_FLAG="-XX:SharedArchiveFile=$archive"
    return 0
  fi

  echo "  预热 CDS 缓存 ${name}（首次约 15-30s；jar 字节变化时会重训）..."
  local warm_log="$LOG_DIR/${name}-cds-warmup.log"
  rm -f "$archive" "$hash_file"
  java --enable-native-access=ALL-UNNAMED \
    ${LOCAL_FAST_JVM_OPTS} \
    ${JAVA_OPTS:-} \
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
      rm -f "$archive" "$hash_file"
      echo "  ⚠  ${name} CDS 预热超时（>120s），跳过 → $warm_log"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  wait "$pid" 2>/dev/null || true

  if [[ -f "$archive" ]]; then
    cds_metadata "$jar" >"$hash_file"
    echo "  ✓ ${name} CDS 缓存就绪 ($(du -h "$archive" 2>/dev/null | cut -f1))"
    __CDS_FLAG="-XX:SharedArchiveFile=$archive"
  else
    rm -f "$hash_file"
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
  echo "  已启动 ${name} pid=${pid} 运行包 build/runtime-jars/${name}.jar 日志 logs/current/app/${name}.log（兼容 logs/app/${name}.log）"
}

wait_postgres() {
  echo "==> 等待 PostgreSQL 就绪..."
  local i
  for i in $(seq 1 90); do
    if docker compose --env-file "$COMPOSE_ENV_FILE" exec -T postgres-primary pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
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
    listed="$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_CONTAINER_BOOTSTRAP" --list 2>/dev/null || true)"
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
  docker logs "$KAFKA_INIT_CONTAINER" >&2 || true
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



# console-api 读写分离开关需要 postgres-replica 容器（docker-compose 用 profile=replica
# 控制是否启动）。这里读取 BATCH_CONSOLE_READ_REPLICA_ENABLED 决定要不要带 --profile replica：
# - true（默认）→ 起 postgres-replica，console-api 启用读路由
# - false       → 不起 replica，console-api 走单库
COMPOSE_PROFILES=()
if [[ "${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}" == "true" ]]; then
  COMPOSE_PROFILES+=(--profile replica)
  echo "==> read-replica 已启用 → 同时启动 postgres-replica（流复制 hot standby）"
else
  echo "==> read-replica 已显式关闭 → 跳过 postgres-replica 容器"
fi

echo "==> Docker Compose 启动基础依赖（postgres / kafka / minio / redis${BATCH_CONSOLE_READ_REPLICA_ENABLED:+ / postgres-replica}）..."
docker compose --env-file "$COMPOSE_ENV_FILE" ${COMPOSE_PROFILES[@]+"${COMPOSE_PROFILES[@]}"} up -d

# postgres / minio / redis / kafka-topics 相互无依赖，并发 wait 节省 5-10s
# minio-init 依赖 minio，仍需串行于 minio healthy 之后
echo "==> 并发等待基础服务就绪（postgres / minio / redis / kafka-topics${BATCH_CONSOLE_READ_REPLICA_ENABLED:+ / postgres-replica}）..."
wait_postgres & _pid_pg=$!
wait_container_healthy "$MINIO_CONTAINER" "MinIO" & _pid_minio=$!
wait_container_healthy "$REDIS_CONTAINER" "Redis" & _pid_redis=$!
wait_kafka_topics_ready & _pid_kafka=$!
_pid_replica=
if [[ "${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}" == "true" ]]; then
  wait_container_healthy "$PG_REPLICA_CONTAINER" "PG Replica" & _pid_replica=$!
fi

_basic_failed=0
for _pid in "$_pid_pg" "$_pid_minio" "$_pid_redis" "$_pid_kafka" ${_pid_replica:+"$_pid_replica"}; do
  if ! wait "$_pid"; then _basic_failed=1; fi
done
if (( _basic_failed == 1 )); then
  echo "ERROR: 部分基础服务等待失败，见上方日志" >&2
  exit 1
fi
unset _pid_pg _pid_minio _pid_redis _pid_kafka _pid_replica _basic_failed _pid

wait_container_exited_zero "$MINIO_INIT_CONTAINER" "MinIO bucket init"

# ── 业务库 DDL 落地（idempotent，CREATE TABLE IF NOT EXISTS）──
# create_biz_tables.sql 同时建 biz.* 业务表 + batch.process_staging（PROCESS WAP staging）。
# Postgres 容器初始化只建库 + 几个 schema/shedlock，业务表必须每次启动 apply 一次，
# 避免新增表（如 P1-7 加的 process_staging）在旧 PG 卷上缺失导致 worker 启动 SQL 异常。
echo "==> 应用业务库 DDL（biz.* + batch.process_staging）..."
if docker exec -i "$PG_CONTAINER" psql -U "${POSTGRES_USER:-batch_user}" -d "${BUSINESS_DB_NAME:-batch_business}" -v ON_ERROR_STOP=1 \
     < "$ROOT/scripts/db/business/create_biz_tables.sql" >/dev/null 2>&1; then
  echo "  业务库 DDL 已 apply"
else
  echo "  ⚠️  业务库 DDL apply 失败（不阻塞启动；详见 docker logs $PG_CONTAINER）"
fi

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
    "worker-process"
    "worker-dispatch"
    "worker-atomic"
  )
  local ports=(
    "${BATCH_ORCHESTRATOR_PORT:-18082}"
    "${BATCH_TRIGGER_PORT:-18081}"
    "${BATCH_CONSOLE_PORT:-18080}"
    "${BATCH_WORKER_IMPORT_PORT:-18083}"
    "${BATCH_WORKER_EXPORT_PORT:-18084}"
    "${BATCH_WORKER_PROCESS_PORT:-18086}"
    "${BATCH_WORKER_DISPATCH_PORT:-18085}"
    "${BATCH_WORKER_ATOMIC_PORT:-18087}"
  )
  local found=0
  local i
  for ((i = 0; i < ${#names[@]}; i++)); do
    local name="${names[$i]}"
    local port="${ports[$i]}"
    local pids
    pids="$(process_listen_pids "$port")"
    [[ -z "$pids" ]] && continue
    while IFS= read -r pid; do
      [[ -z "$pid" ]] && continue
      # 跳过 PID_FILE 里登记且仍活着的进程：那是上一次本脚本启动留下的 in-flight 服务,
      # start_java 会基于 PID_FILE 做幂等跳过。盲杀会让"独立顺序起 worker"场景下
      # 已起来的 trigger / console / 其它 worker 被本次清理误伤。
      if [[ -f "$PID_FILE" ]] && grep -Fq "	${pid}	" "$PID_FILE" 2>/dev/null; then
        continue
      fi
      if (( found == 0 )); then
        echo "  端口 ${port} (${name}) 被占用，清理残留进程..."
      fi
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
WORKERS="${WORKERS:-import,export,process,dispatch,atomic}"

# console-api 读写分离：与 application.yml fallback / docker-compose / .env.example 对齐默认 true。
# 本地裸 jar 若没起 postgres-replica 容器，ReadReplicaRoutingDataSource fail-open 会
# 在前几次请求时打 WARN 后进入 quarantine 自动降级到主库；想完全静音可
# 显式 export BATCH_CONSOLE_READ_REPLICA_ENABLED=false 后再跑本脚本。
export BATCH_CONSOLE_READ_REPLICA_ENABLED="${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}"

echo "==> 启动 Spring Boot 进程（profile=local）..."
# 实测 "orch + trigger + console 三并发" 让 orch 自己被资源竞争拖慢 ~6s（10→16s），
# 抵消了 trigger/console 提前启动的收益。机器瓶颈在 Spring bean 单线程注入，
# 多 JVM 同起反而互相拖累。保留原来的 orch 独占启动节奏。
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

# 合并:把原 PID_FILE 里本轮未触及但仍 alive 的 entry 保留下来。否则
# "顺序独立起 worker"场景下,前一轮启动的 worker 会从 PID_FILE 消失,
# 下次再跑 start-all 时 _clear_occupied_ports 会因为 PID 不在 PID_FILE 而误杀。
if [[ -f "$PID_FILE" ]]; then
  while IFS=$'\t' read -r prev_name prev_pid prev_jar; do
    [[ -z "$prev_name" || -z "$prev_pid" ]] && continue
    if awk -F'\t' -v n="$prev_name" '$1 == n {found=1; exit} END {exit !found}' "$PID_FILE_NEW"; then
      continue   # 本轮已重新写过该 module 的新 PID,以新值为准
    fi
    if kill -0 "$prev_pid" 2>/dev/null; then
      printf '%s\t%s\t%s\n' "$prev_name" "$prev_pid" "$prev_jar" >>"$PID_FILE_NEW"
    fi
  done < "$PID_FILE"
fi
mv "$PID_FILE_NEW" "$PID_FILE"
trap - EXIT

echo ""
echo "全部进程已在后台运行。端口（默认）："
echo "  console-api 18080 | trigger 18081 | orchestrator 18082 | import 18083 | export 18084 | process 18086 | dispatch 18085 | atomic 18087"
echo "  Postgres 15432 | Kafka 19092 | MinIO 19000 | Redis 16379（宿主机映射）"
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
    worker-process) echo "${BATCH_WORKER_PROCESS_PORT:-18086}" ;;
    worker-dispatch) echo "${BATCH_WORKER_DISPATCH_PORT:-18085}" ;;
    worker-atomic) echo "${BATCH_WORKER_ATOMIC_PORT:-18087}" ;;
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
      printf '  │  ✗ %-18s 未就绪  (port %s)  → 查看 logs/current/app/%s.log\n' "$name" "$port" "$name"
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
