#!/usr/bin/env bash
# 用途：sim 脚本公共变量。调用方可先设置 SIM_STAGE_NAME 覆盖 run 前缀。
# 用法：由调用脚本 source scripts/sim/env-common.sh 后使用公共变量或函数。
# sim 脚本公共变量。调用方可先设置 SIM_STAGE_NAME 覆盖 run 前缀。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/sim/env-common.sh must be sourced, not executed" >&2
  exit 2
fi

SIM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export SIM_ROOT
export PYTHONPATH="$SIM_ROOT${PYTHONPATH:+:$PYTHONPATH}"
# shellcheck source=../lib/env-common.sh
source "$SIM_ROOT/scripts/lib/env-common.sh"

SIM_STAGE_NAME="${SIM_STAGE_NAME:-$(basename "$0" .sh)}"
SIM_STAGE_NAME="${SIM_STAGE_NAME#[0-9][0-9]-}"

export BIZ_DATE="${BIZ_DATE:-$(date +%Y-%m-%d)}"
export BATCH_NO="${BATCH_NO:-sim-${SIM_STAGE_NAME}-$(date +%Y%m%d%H%M%S)}"
export RUN_ID="${RUN_ID:-${SIM_STAGE_NAME}-$(date +%Y%m%d%H%M%S)}"
export REPORT_DIR="${REPORT_DIR:-load-tests/target/$RUN_ID}"
export PG_CONTAINER="${PG_CONTAINER:-$BATCH_DEFAULT_POSTGRES_CONTAINER}"
export SFTP_CONTAINER="${SFTP_CONTAINER:-batch-sim-sftp}"
export PG_PLATFORM_DB="${PG_PLATFORM_DB:-$POSTGRES_DB}"
export PG_PLATFORM_USER="${PG_PLATFORM_USER:-${POSTGRES_USER:-$BATCH_DEFAULT_POSTGRES_USERNAME}}"
export PG_BUSINESS_DB="${PG_BUSINESS_DB:-$BUSINESS_DB_NAME}"
# SIM 在本机同时运行基础设施、多个 Java 服务和故障注入进程。所有由阶段脚本触发的
# restart.sh 都继承这个受限堆；调用方显式传入 JAVA_OPTS 时保持原样。
export SIM_JAVA_OPTS="${SIM_JAVA_OPTS:--Xmx384m -XX:MaxMetaspaceSize=256m ${BATCH_JVM_NETWORK_OPTS}}"
export JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS}"
mkdir -p "$REPORT_DIR"
batch_require_python

# 应用服务运行在 Compose 网络时，夹具中的外部依赖必须使用服务名；宿主机
# 直接运行 JVM 时才使用端口映射。统一在这里判定，避免每个 sim 脚本各写一套。
sim_container_stack_active() {
  case "${BATCH_SCRIPT_RUNTIME:-auto}" in
    host) return 1 ;;
    docker) return 0 ;;
    auto) ;;
    *)
      echo "ERROR: BATCH_SCRIPT_RUNTIME must be auto, host, or docker" >&2
      return 2
      ;;
  esac
  local console
  # 故障注入可能暂时停止 Console；仍须按持久 Compose 归属恢复，不能改走宿主 JVM。
  console="$(docker ps --all --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-batch-platform}" \
    --filter label=com.docker.compose.service=console-api --format '{{.ID}}' 2>/dev/null)" || {
      echo "ERROR: cannot inspect the selected Compose application runtime" >&2
      return 2
    }
  [[ -n "$console" ]]
}

sim_mockserver_base_url() {
  if sim_container_stack_active; then
    printf '%s\n' "http://mockserver:1080"
  else
    local runtime_status=$?
    [[ "$runtime_status" == 1 ]] || return "$runtime_status"
    printf '%s\n' "http://localhost:${MOCKSERVER_HOST_PORT}"
  fi
}

# 稳定 expectation ID 只更新本仓库的规则，不重置共享模拟器中的其它场景。
sim_load_mockserver_stubs() {
  curl --fail --silent --show-error --max-time 30 --connect-timeout 5 \
    -X PUT "http://localhost:${MOCKSERVER_HOST_PORT}/mockserver/expectation" \
    -H 'Content-Type: application/json' \
    --data-binary "@$SIM_ROOT/scripts/sim/mockserver-stubs/expectations.json" >/dev/null
}

# 同一宿主机上的多个项目使用独立公钥文件，避免私有 SFTP 轮换覆盖其它环境的信任。
sim_sftp_known_hosts_path() {
  local project="${COMPOSE_PROJECT_NAME:-batch-platform}"
  if [[ ! "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
    echo "ERROR: invalid Compose project for SIM SFTP host-key path" >&2
    return 1
  fi
  printf '/tmp/batch/sim-channel-trust/%s/known_hosts\n' "$project"
}

# Excel 中服务名适用于 Compose；宿主机 JVM 的探针使用实际端口和受控公钥。
sim_prepare_sftp_probe_channels() {
  local sftp_host sftp_port known_hosts_path
  known_hosts_path="$(sim_sftp_known_hosts_path)" || return 1
  read -r sftp_host sftp_port <<< "$(sim_sftp_endpoint)" || return 1
  mkdir -p "$(dirname "$known_hosts_path")"
  sim_write_sftp_known_hosts "$known_hosts_path" "$sftp_host" "$sftp_port" || return 1
  docker exec -i "$PG_CONTAINER" psql -X -q -U "$PG_PLATFORM_USER" -d "$PG_PLATFORM_DB" \
    -v ON_ERROR_STOP=1 -v sftp_host="$sftp_host" -v sftp_port="$sftp_port" \
    -v known_hosts_path="$known_hosts_path" -f /dev/stdin \
    < "$SIM_ROOT/scripts/sim/sql/prepare-sftp-probe-fixtures.sql"
}

sim_sftp_endpoint() {
  if sim_container_stack_active; then
    printf '%s\n' "sftp 22"
  else
    local runtime_status=$?
    [[ "$runtime_status" == 1 ]] || return "$runtime_status"
    printf '%s\n' "${SFTP_HOST:-127.0.0.1} ${SFTP_PORT:-${SFTP_HOST_PORT:-12222}}"
  fi
}

# 本地 SIM 的宿主机客户端只能访问所选容器的回环映射，不能靠端口可达推断归属。
sim_validate_host_endpoint() {
  local name="$1" endpoint="$2" expected_port="$3"
  batch_parse_host_port "$endpoint"
  case "$BATCH_PARSED_HOST" in
    localhost|127.0.0.1|::1) ;;
    *)
      echo "ERROR: SIM $name must target the selected local container" >&2
      return 1
      ;;
  esac
  if [[ ! "$BATCH_PARSED_PORT" =~ ^[0-9]{1,5}$ || "$BATCH_PARSED_PORT" != "$expected_port" ]]; then
    echo "ERROR: SIM $name port does not match the selected container mapping" >&2
    return 1
  fi
  if (( 10#$BATCH_PARSED_PORT < 1 || 10#$BATCH_PARSED_PORT > 65535 )); then
    echo "ERROR: SIM $name has an invalid port" >&2
    return 1
  fi
}

sim_validate_http_endpoint() {
  local name="$1" endpoint="$2" expected_port="$3" authority
  if [[ "$endpoint" != http://* ]]; then
    echo "ERROR: SIM $name must use the selected local HTTP service" >&2
    return 1
  fi
  authority="${endpoint#http://}"
  authority="${authority%/}"
  sim_validate_host_endpoint "$name" "$authority" "$expected_port"
}

sim_validate_published_port() {
  local container="$1" container_port="$2" expected_port="$3" published_ports
  published_ports="$(docker inspect --format \
    "{{range (index .NetworkSettings.Ports \"$container_port\")}}{{println .HostPort}}{{end}}" \
    "$container")" || return 1
  if ! printf '%s\n' "$published_ports" | grep -Fx -- "$expected_port" >/dev/null; then
    echo "ERROR: SIM $container does not publish the selected host port" >&2
    return 1
  fi
}

# 在 reset、清理或启动任何进程前固定资源归属。应用与 SDK 使用不同配置键，
# 两者都必须指向同一个受管 broker，不能各自退回其它本地环境的默认值。
sim_validate_infrastructure_targets() {
  case "${BATCH_SCRIPT_RUNTIME:-auto}" in
    auto|host|docker) ;;
    *)
      echo "ERROR: BATCH_SCRIPT_RUNTIME must be auto, host, or docker" >&2
      return 1
      ;;
  esac
  # shellcheck source=../lib/destructive-ops.sh
  source "$SIM_ROOT/scripts/lib/destructive-ops.sh"
  local container
  for container in "$PG_CONTAINER" "${KAFKA_CONTAINER:-$BATCH_DEFAULT_KAFKA_CONTAINER}" \
      "${MINIO_CONTAINER:-$BATCH_DEFAULT_MINIO_CONTAINER}" "${REDIS_CONTAINER:-$BATCH_DEFAULT_REDIS_CONTAINER}"; do
    batch_require_compose_container "$container" || return 1
  done

  sim_validate_host_endpoint KAFKA_HOST_BOOTSTRAP "$KAFKA_HOST_BOOTSTRAP" "$KAFKA_HOST_PORT" || return 1
  sim_validate_host_endpoint KAFKA_BOOTSTRAP "${KAFKA_BOOTSTRAP:-$KAFKA_HOST_BOOTSTRAP}" "$KAFKA_HOST_PORT" || return 1
  sim_validate_published_port "${KAFKA_CONTAINER:-$BATCH_DEFAULT_KAFKA_CONTAINER}" 9092/tcp "$KAFKA_HOST_PORT" || return 1

  local container_runtime=0 app_bootstrap
  if sim_container_stack_active; then
    container_runtime=1
    app_bootstrap="${BATCH_KAFKA_BOOTSTRAP_SERVERS:-$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP}"
    if [[ "$app_bootstrap" != "$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP" ]]; then
      echo "ERROR: SIM BATCH_KAFKA_BOOTSTRAP_SERVERS must use the selected Compose broker service" >&2
      return 1
    fi
  else
    local runtime_status=$?
    [[ "$runtime_status" == 1 ]] || return "$runtime_status"
    batch_configure_local_jvm_port_env true || return 1
    batch_configure_local_jvm_control_plane_env
    sim_validate_http_endpoint BATCH_ORCHESTRATOR_BASE_URL "$BATCH_ORCHESTRATOR_BASE_URL" "$ORCHESTRATOR_PORT" || return 1
    sim_validate_http_endpoint BATCH_TRIGGER_BASE_URL "$BATCH_TRIGGER_BASE_URL" "$TRIGGER_PORT" || return 1
    app_bootstrap="${BATCH_KAFKA_BOOTSTRAP_SERVERS:-localhost:$BATCH_DEFAULT_KAFKA_HOST_PORT}"
    sim_validate_host_endpoint BATCH_KAFKA_BOOTSTRAP_SERVERS "$app_bootstrap" "$KAFKA_HOST_PORT" || return 1
  fi

  local sftp_exists=0 sftp_host sftp_port sftp_id
  sftp_id="$(docker ps --all --filter "name=^/${SFTP_CONTAINER}$" --format '{{.ID}}')" || {
    echo "ERROR: cannot inventory the selected SIM SFTP container" >&2
    return 1
  }
  if [[ -n "$sftp_id" ]]; then
    batch_require_compose_container "$SFTP_CONTAINER" || return 1
    sftp_exists=1
  fi
  if [[ "$container_runtime" == 0 ]]; then
    read -r sftp_host sftp_port <<<"$(sim_sftp_endpoint)" || return 1
    sim_validate_host_endpoint SFTP "$(batch_format_host_port "$sftp_host" "$sftp_port")" \
      "${SFTP_HOST_PORT:-12222}" || return 1
    if [[ "$sftp_exists" == 1 ]]; then
      sim_validate_published_port "$SFTP_CONTAINER" 22/tcp "${SFTP_HOST_PORT:-12222}" || return 1
    fi
  fi
}

# 本地 SIM 的 SSH 信任来自所管理 SFTP 容器的公钥；与源文件使用同一共享目录。
# 配置文件保留供渠道重试使用，临时文件在失败时回收，成功原子替换。
sim_write_sftp_known_hosts() {
  local known_hosts_path="$1" sftp_host="$2" sftp_port="$3"
  if [[ ! "$sftp_host" =~ ^[A-Za-z0-9._:-]+$ || ! "$sftp_port" =~ ^[0-9]{1,5}$ ]]; then
    echo "ERROR: invalid SIM SFTP endpoint for host-key pinning" >&2
    return 1
  fi
  sftp_port=$((10#$sftp_port))
  if (( sftp_port < 1 || sftp_port > 65535 )); then
    echo "ERROR: invalid SIM SFTP port for host-key pinning" >&2
    return 1
  fi
  local public_keys
  if ! public_keys="$(docker exec "$SFTP_CONTAINER" sh -c 'cat /etc/ssh/ssh_host_*_key.pub')"; then
    echo "ERROR: cannot read public host keys from managed SIM SFTP container" >&2
    return 1
  fi
  mkdir -p "$(dirname "$known_hosts_path")" || return 1
  local temporary_hosts
  temporary_hosts="$(mktemp "${known_hosts_path}.XXXXXX")" || return 1
  if printf '%s\n' "$public_keys" | awk -v aliases="$sftp_host,[$sftp_host]:$sftp_port" '
    NF == 0 { next }
    $1 !~ /^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521))$/ { invalid = 1; next }
    NF < 2 || $2 !~ /^[A-Za-z0-9+\/]+={0,2}$/ { invalid = 1; next }
    { print aliases " " $1 " " $2; valid++ }
    END { if (invalid || valid == 0) exit 1 }
  ' >"$temporary_hosts" && chmod 0644 "$temporary_hosts" && mv -f "$temporary_hosts" "$known_hosts_path"; then
    return 0
  fi
  # 仅删除本次 mktemp 返回的路径；既有配置不在失败清理范围。
  rm -f "$temporary_hosts"
  echo "ERROR: cannot validate or publish managed SIM SFTP public host keys" >&2
  return 1
}

# 按起始 BIZ_DATE 计算第 N 轮的业务日期(base + N-1 天),供多轮 sim 错开 bizDate、
# 避免同 job+bizDate 被幂等去重撞掉。
biz_date_for_round() {
  "$PYTHON_BIN" - "$BIZ_DATE" "$1" <<'PY'
import datetime as dt
import sys

base = dt.date.fromisoformat(sys.argv[1])
print((base + dt.timedelta(days=int(sys.argv[2]) - 1)).isoformat())
PY
}
