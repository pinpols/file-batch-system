#!/usr/bin/env bash
# =============================================================
# restart.sh - 单个或多个模块热重启
#
# 用法：
#   ./scripts/local/restart.sh <module> [module...]
#
# 支持的模块名：
#   orchestrator  trigger  console
#   worker-import  worker-export  worker-process  worker-dispatch  worker-atomic
#
# 示例：
#   ./scripts/local/restart.sh trigger
#   ./scripts/local/restart.sh orchestrator trigger
#   ./scripts/local/restart.sh console
#   BUILD=1 ./scripts/local/restart.sh trigger   # 重启前先重新打包
#
# 说明：
#   - 按依赖顺序重启：orchestrator 必须在 trigger/console/worker 之前就绪
#   - 若 orchestrator 在重启列表中，等它 UP 后再启动其他服务
#   - 每次重启会覆盖对应模块当前日志（logs/current/app/<module>.log，兼容 logs/app/<module>.log）
# =============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# 记录调用方是否显式指定了容器内 S3 endpoint。env-common 为裸 JVM
# 自动补出的 localhost:19000 不能直接传进 Compose 网络。
_BATCH_S3_ENDPOINT_EXPLICIT=0
if [[ -n "${BATCH_S3_ENDPOINT+x}" ]]; then
  _BATCH_S3_ENDPOINT_EXPLICIT=1
else
  for _env_file in "${COMPOSE_ENV_FILE:-$ROOT/.env.local}" "$ROOT/.env"; do
    if [[ -f "$_env_file" ]] && grep -Eq '^[[:space:]]*(export[[:space:]]+)?BATCH_S3_ENDPOINT=' "$_env_file"; then
      _BATCH_S3_ENDPOINT_EXPLICIT=1
      break
    fi
  done
fi
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
# shellcheck source=../lib/process.sh
source "$ROOT/scripts/lib/process.sh"
cd "$ROOT"

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env.local}"
if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$COMPOSE_ENV_FILE"
  set +a
fi
batch_configure_local_jvm_database_env
export BATCH_LOCALE="${BATCH_LOCALE:-C.UTF-8}"
export LANG="$BATCH_LOCALE"
export LC_ALL="$BATCH_LOCALE"

LOG_DIR="$(log_current_dir "$ROOT" app app)"
RUNTIME_JAR_DIR="$ROOT/build/runtime-jars"
CDS_DIR="$ROOT/build/cds"
PID_FILE="$(log_pid_file "$ROOT" start-all.pids)"
mkdir -p "$LOG_DIR" "$CDS_DIR"

# 与 start-all.sh 保持一致的本地 dev 启动加速参数（说明见 start-all.sh）
LOCAL_FAST_JVM_OPTS="${LOCAL_FAST_JVM_OPTS:--XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off}"

# AppCDS：当前本地启动栈下 native-access 状态可能错位（dump/runtime 不一致 →
# MyBatis/Spring MVC/Tomcat 内部类 NoClassDefFoundError）。统一关 CDS；说明同 start-all.sh。
SKIP_CDS="${SKIP_CDS:-1}"
CDS_ARCHIVE_STAMP="${CDS_ARCHIVE_STAMP:-v3-share-off}"

# AppCDS：同 start-all.sh，见那里的完整说明（jar SHA-256 + jar 元数据 + CDS 指纹判重）
__CDS_FLAG=""
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "ERROR: sha256sum or shasum is required" >&2
    exit 2
  fi
}

cds_metadata() {
  local jar="$1"
  local jar_hash jar_stat
  jar_hash="$(sha256_file "$jar")"
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
      echo "  ⚠  ${name} CDS 预热超时，跳过 → $warm_log"
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  wait "$pid" 2>/dev/null || true

  if [[ -f "$archive" ]]; then
    cds_metadata "$jar" >"$hash_file"
    echo "  ✓ ${name} CDS 缓存就绪"
    __CDS_FLAG="-XX:SharedArchiveFile=$archive"
  else
    rm -f "$hash_file"
    echo "  ⚠  ${name} CDS 预热未生成 .jsa，跳过 → $warm_log"
  fi
}

# ── 端口映射 ──────────────────────────────────────────────────
port_for() {
  case "$1" in
    orchestrator)    echo "${BATCH_ORCHESTRATOR_PORT:-18082}" ;;
    trigger)         echo "${BATCH_TRIGGER_PORT:-18081}" ;;
    console)         echo "${BATCH_CONSOLE_PORT:-18080}" ;;
    worker-import)   echo "${BATCH_WORKER_IMPORT_PORT:-18083}" ;;
    worker-export)   echo "${BATCH_WORKER_EXPORT_PORT:-18084}" ;;
    worker-process)  echo "${BATCH_WORKER_PROCESS_PORT:-18086}" ;;
    worker-dispatch) echo "${BATCH_WORKER_DISPATCH_PORT:-18085}" ;;
    worker-atomic)      echo "${BATCH_WORKER_ATOMIC_PORT:-18087}" ;;
    *) echo "ERROR: 未知模块 '$1'" >&2; exit 1 ;;
  esac
}

# 容器栈与裸 JVM 共用本脚本。容器模式必须通过 Compose 重建服务，不能
# 按宿主端口查 PID 后 kill；Docker Desktop 的端口代理并不是业务 JVM。
container_name_for() {
  case "$1" in
    orchestrator)    echo batch-orchestrator ;;
    trigger)         echo batch-trigger ;;
    console)         echo batch-console-api ;;
    worker-import)   echo batch-worker-import ;;
    worker-export)   echo batch-worker-export ;;
    worker-process)  echo batch-worker-process ;;
    worker-dispatch) echo batch-worker-dispatch ;;
    worker-atomic)   echo batch-worker-atomic ;;
    *) return 1 ;;
  esac
}

compose_service_for() {
  case "$1" in
    orchestrator)    echo orchestrator ;;
    trigger)         echo trigger ;;
    console)         echo console-api ;;
    worker-import)   echo worker-import ;;
    worker-export)   echo worker-export ;;
    worker-process)  echo worker-process ;;
    worker-dispatch) echo worker-dispatch ;;
    worker-atomic)   echo worker-atomic ;;
    *) return 1 ;;
  esac
}

is_containerized_module() {
  local name="$1" container
  container="$(container_name_for "$name")" || return 1
  docker info >/dev/null 2>&1 || return 1
  docker inspect "$container" >/dev/null 2>&1
}

compose_restart_module() {
  local name="$1" service
  service="$(compose_service_for "$name")"
  local app_java_opts="${BATCH_APP_JAVA_OPTS:---enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=192m}"
  if [[ -n "${JAVA_OPTS:-}" ]]; then
    app_java_opts+=" ${JAVA_OPTS}"
  fi
  local compose_args=(docker compose)
  if [[ -f "$COMPOSE_ENV_FILE" ]]; then
    compose_args+=(--env-file "$COMPOSE_ENV_FILE")
  fi
  compose_args+=(-f docker-compose.yml -f docker/compose/app.yml --profile apps --profile replica up -d --no-deps --force-recreate "$service")
  local compose_s3_endpoint="${BATCH_S3_ENDPOINT:-http://minio:9000}"
  # .env.local 通常为裸 JVM 保留 localhost:19000；这个地址在容器网络内
  # 一定指向当前容器自身，不能视为容器 S3 配置。
  if [[ "$_BATCH_S3_ENDPOINT_EXPLICIT" != 1 \
        || "$compose_s3_endpoint" == *://localhost:* \
        || "$compose_s3_endpoint" == *://127.0.0.1:* ]]; then
    compose_s3_endpoint=http://minio:9000
  fi
  BATCH_S3_ENDPOINT="$compose_s3_endpoint" BATCH_APP_JAVA_OPTS="$app_java_opts" "${compose_args[@]}"
}

# ── Maven 模块名（用于 build） ────────────────────────────────
maven_module_for() {
  case "$1" in
    orchestrator)    echo "batch-orchestrator" ;;
    trigger)         echo "batch-trigger" ;;
    console)         echo "batch-console-api" ;;
    worker-import)   echo "batch-worker-import" ;;
    worker-export)   echo "batch-worker-export" ;;
    worker-process)  echo "batch-worker-process" ;;
    worker-dispatch) echo "batch-worker-dispatch" ;;
    worker-atomic)      echo "batch-worker-atomic" ;;
  esac
}

# ── Maven 模块目录路径(用于 -pl / target)─────────────────────
# 6 worker 已迁到 batch-worker/ 下,目录路径 ≠ artifactId(对齐 build-apps.sh DIRS)。
maven_dir_for() {
  case "$1" in
    orchestrator)    echo "batch-orchestrator" ;;
    trigger)         echo "batch-trigger" ;;
    console)         echo "batch-console-api" ;;
    worker-import)   echo "batch-worker/import" ;;
    worker-export)   echo "batch-worker/export" ;;
    worker-process)  echo "batch-worker/process" ;;
    worker-dispatch) echo "batch-worker/dispatch" ;;
    worker-atomic)   echo "batch-worker/atomic" ;;
  esac
}

# ── 停止：kill 端口上的进程 ───────────────────────────────────
stop_module() {
  local name="$1"
  if is_containerized_module "$name"; then
    echo "  ${name} 由 Compose 管理，跳过宿主端口 kill"
    return 0
  fi
  local port
  port="$(port_for "$name")"
  local pids
  # 只停监听进程。纯端口连接扫描还可能返回连到该端口的客户端，
  # 重启时可能误杀依赖此服务的 worker。
  pids="$(process_listen_pids "$port")"
  if [ -n "$pids" ]; then
    echo "  停止 ${name}（端口 ${port}，pid=${pids}）"
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
  else
    echo "  ${name} 未运行（端口 ${port} 空闲）"
  fi
}

# ── 构建单个模块 ──────────────────────────────────────────────
build_module() {
  local name="$1"
  local mod
  mod="$(maven_module_for "$name")"
  local dir
  dir="$(maven_dir_for "$name")"
  echo "  构建 $mod ..."
  _MVND_BIN="${HOME}/.local/bin/mvnd"
  if [[ -x "$_MVND_BIN" ]]; then
    export MVND_HOME="${HOME}/.local/share/maven-mvnd-1.0.5-darwin-aarch64"
    _MVN="$_MVND_BIN"
  else
    _MVN=$(command -v mvnd 2>/dev/null || command -v mvn)
  fi
  find "$dir/target" -maxdepth 1 -name "${mod}-*-exec.jar" -delete 2>/dev/null || true
  "$_MVN" -pl "$dir" -am clean package -DskipTests -q
  jar="$(find "$dir/target" -maxdepth 1 -name "${mod}-*-exec.jar" 2>/dev/null | head -1 || true)"
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "ERROR: 未找到可执行 exec jar: $dir/target/${mod}-*-exec.jar" >&2
    exit 1
  fi
  _bytes="$(wc -c <"$jar" | awk '{print $1}')"
  if [[ "${_bytes:-0}" -lt 4096 ]]; then
    echo "ERROR: $jar 仅 ${_bytes} 字节，疑似损坏（正常 exec jar 至少数 MB）。" >&2
    exit 1
  fi
  cp "$jar" "$RUNTIME_JAR_DIR/$name.jar"
  echo "  构建完成 → build/runtime-jars/$name.jar"
}

# ── 启动模块 ──────────────────────────────────────────────────
start_module() {
  local name="$1"
  if is_containerized_module "$name"; then
    echo "  重建容器 ${name}"
    compose_restart_module "$name"
    local container
    container="$(container_name_for "$name")"
    for _ in $(seq 1 90); do
      if docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null | grep -q healthy; then
        echo "  ${name} 容器已就绪"
        return 0
      fi
      sleep 2
    done
    echo "ERROR: ${name} 容器在超时时间内未健康，请检查 docker logs ${container}" >&2
    return 1
  fi
  local jar="$RUNTIME_JAR_DIR/$name.jar"
  if [ ! -f "$jar" ]; then
    echo "ERROR: 未找到 ${jar}，请先执行 ./scripts/local/build-apps.sh" >&2
    exit 1
  fi
  warm_cds "$name" "$jar"
  nohup java --enable-native-access=ALL-UNNAMED ${LOCAL_FAST_JVM_OPTS} ${__CDS_FLAG} ${JAVA_OPTS:-} \
    -jar "$jar" --spring.profiles.active=local \
    >"$LOG_DIR/$name.log" 2>&1 &
  local pid=$!
  echo "  已启动 $name pid=$pid → logs/current/app/$name.log（兼容 logs/app/$name.log）"

  # 更新 PID 文件（若存在）
  if [ -f "$PID_FILE" ]; then
    # 先删除旧条目，再追加新条目
    local tmp
    tmp="$(mktemp)"
    grep -v "^$name	" "$PID_FILE" > "$tmp" 2>/dev/null || true
    printf '%s\t%s\t%s\n' "$name" "$pid" "$jar" >> "$tmp"
    mv "$tmp" "$PID_FILE"
  fi
}

# ── 等待 orchestrator 健康 ────────────────────────────────────
wait_orchestrator() {
  local port="${BATCH_ORCHESTRATOR_PORT:-18082}"
  local url="http://127.0.0.1:${port}/actuator/health"
  echo "  等待 orchestrator 就绪（${url}）..."
  for i in $(seq 1 60); do
    sleep 3
    if curl -sf --connect-timeout 2 --max-time 5 "$url" 2>/dev/null \
        | grep -q '"status":"UP"'; then
      echo "  orchestrator 已就绪"
      return 0
    fi
  done
  echo "ERROR: orchestrator 在超时时间内未就绪，请检查 logs/current/app/orchestrator.log" >&2
  exit 1
}

# ══════════════════════════════════════════════════════════════
# 主流程
# ══════════════════════════════════════════════════════════════

if [ $# -eq 0 ]; then
  echo "用法: $0 <module> [module...]"
  echo "支持: orchestrator trigger console worker-import worker-export worker-process worker-dispatch worker-atomic"
  exit 1
fi

TARGETS=("$@")

# 校验所有模块名合法
for name in "${TARGETS[@]}"; do
  port_for "$name" >/dev/null
done

echo "==> 停止目标模块..."
for name in "${TARGETS[@]}"; do
  stop_module "$name"
done

sleep 2

# 构建（BUILD=1 时）
if [ "${BUILD:-0}" == "1" ]; then
  echo "==> 构建目标模块（BUILD=1）..."
  for name in "${TARGETS[@]}"; do
    build_module "$name"
  done
fi

echo "==> 按依赖顺序启动..."

# console-api 读写分离：与 start-all.sh / application.yml / docker-compose / .env.example 对齐默认 true。
# 想完全关掉路由：显式 export BATCH_CONSOLE_READ_REPLICA_ENABLED=false 后再 restart console
export BATCH_CONSOLE_READ_REPLICA_ENABLED="${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}"

# 定义全局启动顺序
ORDERED=(orchestrator trigger console worker-import worker-export worker-process worker-dispatch worker-atomic)

need_wait_orch=false
for name in "${TARGETS[@]}"; do
  [ "$name" == "orchestrator" ] && need_wait_orch=true && break
done

for name in "${ORDERED[@]}"; do
  # 判断是否在本次重启列表中
  in_targets=false
  for t in "${TARGETS[@]}"; do
    [ "$t" == "$name" ] && in_targets=true && break
  done
  $in_targets || continue

  start_module "$name"

  # orchestrator 启动后必须等它 UP，再启动下游
  if [ "$name" == "orchestrator" ] && $need_wait_orch; then
    wait_orchestrator
  fi
done

echo ""
echo "重启完成。"
