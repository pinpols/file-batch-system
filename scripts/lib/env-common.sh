#!/usr/bin/env bash
# 公共本地环境入口。只能被 source,不要直接执行。

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "scripts/lib/env-common.sh must be sourced, not executed" >&2
  exit 2
fi

if [[ -z "${BATCH_ENV_COMMON_ROOT:-}" ]]; then
  BATCH_ENV_COMMON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

# shellcheck source=runtime-defaults.sh
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/runtime-defaults.sh"
# shellcheck source=python-runtime.sh
source "$BATCH_ENV_COMMON_ROOT/scripts/lib/python-runtime.sh"

# JVM 网络地址选择策略：保留双栈，但多地址解析时优先 IPv4。
# 所有本地启动脚本从这里取值，避免参数在各脚本中重复维护。
export BATCH_JVM_NETWORK_OPTS="${BATCH_JVM_NETWORK_OPTS:--Djava.net.preferIPv4Stack=false -Djava.net.preferIPv6Addresses=false}"
# Kafka 官方镜像内 CLI 目录。容器镜像布局变化时只需覆盖这一处。
export KAFKA_CONTAINER_BIN_DIR="${KAFKA_CONTAINER_BIN_DIR:-$BATCH_DEFAULT_KAFKA_CONTAINER_BIN_DIR}"

batch_source_env_file() {
  local env_file="${1:-${COMPOSE_ENV_FILE:-$BATCH_ENV_COMMON_ROOT/.env.local}}"
  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
}

# 将独立的 host 与 port 组成 URI authority / Kafka bootstrap 形式。
# IPv6 字面量必须加方括号；已加方括号的输入保持幂等。数据库客户端的
# `-h` 参数仍使用原始 host，不要把本函数的结果传给 libpq。
batch_format_host_port() {
  local host="${1:-}" port="${2:-}"
  if [[ "$host" == \[*\] ]]; then
    printf '%s:%s\n' "$host" "$port"
  elif [[ "$host" == *:* ]]; then
    printf '[%s]:%s\n' "$host" "$port"
  else
    printf '%s:%s\n' "$host" "$port"
  fi
}

# 解析单个 host:port authority。支持 hostname、IPv4、裸 IPv6 和 [IPv6]:port。
# 结果通过 BATCH_PARSED_HOST / BATCH_PARSED_PORT 返回，避免调用方重复实现
# `${value%%:*}` 这种会截断 IPv6 的脆弱解析。
batch_parse_host_port() {
  local value="${1:-}"
  if [[ "$value" == \[*\]:* ]]; then
    BATCH_PARSED_HOST="${value#\[}"
    BATCH_PARSED_HOST="${BATCH_PARSED_HOST%%\]*}"
    BATCH_PARSED_PORT="${value##*:}"
  elif [[ "$value" == *:* && "$value" != *:*:* ]]; then
    BATCH_PARSED_HOST="${value%:*}"
    BATCH_PARSED_PORT="${value##*:}"
  elif [[ "$value" == *:*:* ]]; then
    BATCH_PARSED_HOST="$value"
    BATCH_PARSED_PORT=""
  else
    BATCH_PARSED_HOST="$value"
    BATCH_PARSED_PORT=""
  fi
  export BATCH_PARSED_HOST BATCH_PARSED_PORT
}

batch_load_default_env() {
  if [[ -n "${BATCH_ENV_LOADED:-}" ]]; then
    return 0
  fi

  COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-$BATCH_ENV_COMMON_ROOT/.env.local}"
  if [[ "$COMPOSE_ENV_FILE" != "$BATCH_ENV_COMMON_ROOT/.env" && -f "$BATCH_ENV_COMMON_ROOT/.env" ]]; then
    batch_source_env_file "$BATCH_ENV_COMMON_ROOT/.env"
  fi
  batch_source_env_file "$COMPOSE_ENV_FILE"

  export BATCH_TIMEZONE_DEFAULT_ZONE="${BATCH_TIMEZONE_DEFAULT_ZONE:-Asia/Shanghai}"
  export TZ="${TZ:-$BATCH_TIMEZONE_DEFAULT_ZONE}"
  export BATCH_LOCALE="${BATCH_LOCALE:-C.UTF-8}"
  export LANG="${LANG:-$BATCH_LOCALE}"
  export LC_ALL="${LC_ALL:-$BATCH_LOCALE}"
  batch_configure_python_runtime

  export POSTGRES_PORT="${POSTGRES_PORT:-$BATCH_DEFAULT_POSTGRES_PORT}"
  export POSTGRES_DB="${POSTGRES_DB:-$BATCH_DEFAULT_POSTGRES_DATABASE}"
  export POSTGRES_USER="${POSTGRES_USER:-$BATCH_DEFAULT_POSTGRES_USERNAME}"
  export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$BATCH_DEFAULT_POSTGRES_PASSWORD}"
  export BUSINESS_DB_NAME="${BUSINESS_DB_NAME:-$BATCH_DEFAULT_BUSINESS_DATABASE}"
  export PG_CONTAINER="${PG_CONTAINER:-$BATCH_DEFAULT_POSTGRES_CONTAINER}"
  export BATCH_DEFAULT_TENANT_ID="${BATCH_DEFAULT_TENANT_ID:-default-tenant}"
  export BATCH_DEV_FIXTURE_TENANTS="${BATCH_DEV_FIXTURE_TENANTS:-ta,tb,tc,tx,default-tenant}"

  export PGHOST="${PGHOST:-${PG_PRIMARY_HOST:-localhost}}"
  export PGPORT="${PGPORT:-${PG_PRIMARY_PORT:-$POSTGRES_PORT}}"
  export PGUSER="${PGUSER:-$POSTGRES_USER}"
  export PGPASSWORD="${PGPASSWORD:-$POSTGRES_PASSWORD}"
  export PGDATABASE="${PGDATABASE:-$POSTGRES_DB}"
  export PLATFORM_DB="${PLATFORM_DB:-$POSTGRES_DB}"
  export BUSINESS_DB="${BUSINESS_DB:-$BUSINESS_DB_NAME}"

  export CONSOLE_API_PORT="${CONSOLE_API_PORT:-${CONSOLE_PORT:-$BATCH_DEFAULT_CONSOLE_PORT}}"
  export TRIGGER_PORT="${TRIGGER_PORT:-$BATCH_DEFAULT_TRIGGER_PORT}"
  export ORCHESTRATOR_PORT="${ORCHESTRATOR_PORT:-$BATCH_DEFAULT_ORCHESTRATOR_PORT}"
  export WORKER_IMPORT_PORT="${WORKER_IMPORT_PORT:-$BATCH_DEFAULT_WORKER_IMPORT_PORT}"
  export WORKER_EXPORT_PORT="${WORKER_EXPORT_PORT:-$BATCH_DEFAULT_WORKER_EXPORT_PORT}"
  export WORKER_DISPATCH_PORT="${WORKER_DISPATCH_PORT:-$BATCH_DEFAULT_WORKER_DISPATCH_PORT}"
  export WORKER_PROCESS_PORT="${WORKER_PROCESS_PORT:-$BATCH_DEFAULT_WORKER_PROCESS_PORT}"
  export WORKER_ATOMIC_PORT="${WORKER_ATOMIC_PORT:-$BATCH_DEFAULT_WORKER_ATOMIC_PORT}"

  export CONSOLE_BASE="${CONSOLE_BASE:-http://localhost:${CONSOLE_API_PORT}}"
  export TRIGGER_BASE="${TRIGGER_BASE:-http://localhost:${TRIGGER_PORT}}"
  export ORCH_BASE="${ORCH_BASE:-http://localhost:${ORCHESTRATOR_PORT}}"
  export CONSOLE_BASE_URL="${CONSOLE_BASE_URL:-$CONSOLE_BASE}"
  export TRIGGER_BASE_URL="${TRIGGER_BASE_URL:-$TRIGGER_BASE}"
  export ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-$ORCH_BASE}"

  export BATCH_ORCHESTRATOR_URL="${BATCH_ORCHESTRATOR_URL:-$ORCH_BASE}"
  export BATCH_CONSOLE_URL="${BATCH_CONSOLE_URL:-$CONSOLE_BASE}"

  export BATCH_INTERNAL_SECRET="${BATCH_INTERNAL_SECRET:-internal-secret}"
  export INTERNAL_SECRET="${INTERNAL_SECRET:-$BATCH_INTERNAL_SECRET}"

  export MINIO_API_PORT="${MINIO_API_PORT:-$BATCH_DEFAULT_MINIO_API_PORT}"
  export MINIO_BUCKET="${MINIO_BUCKET:-${BATCH_S3_BUCKET:-$BATCH_DEFAULT_MINIO_BUCKET}}"
  export BATCH_S3_BUCKET="${BATCH_S3_BUCKET:-$MINIO_BUCKET}"
  export BATCH_S3_ENDPOINT="${BATCH_S3_ENDPOINT:-http://localhost:${MINIO_API_PORT}}"
  export BATCH_S3_ACCESS_KEY="${BATCH_S3_ACCESS_KEY:-${MINIO_ROOT_USER:-$BATCH_DEFAULT_MINIO_ACCESS_KEY}}"
  export BATCH_S3_SECRET_KEY="${BATCH_S3_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-$BATCH_DEFAULT_MINIO_SECRET_KEY}}"

  export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-$BATCH_DEFAULT_KAFKA_HOST_PORT}"
  export KAFKA_HOST_BOOTSTRAP="${KAFKA_HOST_BOOTSTRAP:-$(batch_format_host_port "${KAFKA_HOST:-localhost}" "$KAFKA_HOST_PORT")}"
  export KAFKA_CONTAINER_BOOTSTRAP="${KAFKA_CONTAINER_BOOTSTRAP:-$BATCH_DEFAULT_KAFKA_CONTAINER_BOOTSTRAP}"

  export BATCH_ENV_LOADED=1
}

# 统一 PostgreSQL 客户端入口。默认优先使用宿主机 psql；需要时可设置
# BATCH_PG_CLIENT_MODE=docker 复用 PG 容器内的 psql。无宿主机客户端且无可用
# 容器时明确失败，避免脚本误把“未执行 SQL”当成成功。
psql() {
  local mode="${BATCH_PG_CLIENT_MODE:-auto}"
  local psql_bin="${BATCH_PSQL_BIN:-}"
  local container="${PG_CONTAINER:-$BATCH_DEFAULT_POSTGRES_CONTAINER}"
  local -a args=("$@")
  local input_file=""
  local i

  if [[ "$mode" == "host" || "$mode" == "auto" ]] && [[ -n "$psql_bin" ]]; then
    if [[ ! -x "$psql_bin" ]]; then
      printf 'PostgreSQL client unavailable: BATCH_PSQL_BIN is not executable: %s\n' "$psql_bin" >&2
      return 127
    fi
  elif [[ "$mode" == "host" || "$mode" == "auto" ]] && command -v psql >/dev/null 2>&1; then
    psql_bin="$(command -v psql)"
  fi

  if [[ ( "$mode" == "host" || "$mode" == "auto" ) && -n "$psql_bin" ]]; then
    command "$psql_bin" "${args[@]}"
    return
  fi

  if [[ "$mode" == "python" || ( "$mode" == "auto" && -z "$psql_bin" ) ]]; then
    if command -v "${PYTHON_BIN:-python3}" >/dev/null 2>&1 && "${PYTHON_BIN:-python3}" -c 'import psycopg' >/dev/null 2>&1; then
      "${PYTHON_BIN:-python3}" "$BATCH_ENV_COMMON_ROOT/scripts/lib/postgres-client.py" "${args[@]}"
      return
    fi
    if [[ "$mode" == "python" ]]; then
      printf 'PostgreSQL Python client unavailable: install psycopg or choose BATCH_PG_CLIENT_MODE=host|docker\n' >&2
      return 127
    fi
  fi

  if [[ "$mode" == "host" ]]; then
    printf 'PostgreSQL client unavailable: set BATCH_PSQL_BIN or install psql\n' >&2
    return 127
  fi

  if ! command -v docker >/dev/null 2>&1 || ! docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q '^true$'; then
    printf 'PostgreSQL client unavailable: install psql or start container %s\n' "$container" >&2
    return 127
  fi

  # 容器内使用服务名/Unix 网络，不应把宿主机 -h/-p 继续传入容器。
  local -a docker_args=()
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      -h|-p)
        ((i += 1))
        ;;
      -f)
        ((i += 1))
        if ((i >= ${#args[@]})); then
          printf 'PostgreSQL Docker client: -f requires a SQL file\n' >&2
          return 2
        fi
        if [[ "${args[$i]}" == "-" ]]; then
          docker_args+=(-f -)
        elif [[ -n "$input_file" ]]; then
          printf 'PostgreSQL Docker client supports one host SQL file per invocation\n' >&2
          return 2
        elif [[ ! -r "${args[$i]}" ]]; then
          printf 'PostgreSQL Docker client cannot read SQL file: %s\n' "${args[$i]}" >&2
          return 2
        else
          input_file="${args[$i]}"
        fi
        ;;
      *)
        docker_args+=("${args[$i]}")
        ;;
    esac
  done
  if [[ -n "$input_file" ]]; then
    docker exec -i "$container" psql "${docker_args[@]}" < "$input_file"
  else
    docker exec -i "$container" psql "${docker_args[@]}"
  fi
}

batch_require_internal_secret() {
  if [[ -z "${BATCH_INTERNAL_SECRET:-}" || "$BATCH_INTERNAL_SECRET" == "internal-secret" ]]; then
    echo "BATCH_INTERNAL_SECRET is required and must not be the default internal-secret" >&2
    return 2
  fi
}

# 裸 JVM 本地联调使用宿主机端口，而 .env.local 同时还会被 Docker Compose
# 读取，其中部分地址使用容器 DNS。调用方在加载默认 env 后显式调用本函数，
# 将通用 PostgreSQL 配置映射到各应用的强类型配置项。
batch_configure_local_jvm_database_env() {
  local replica_authority
  export BATCH_PLATFORM_DB_URL="${BATCH_PLATFORM_DB_URL:-jdbc:postgresql://$(batch_format_host_port "${PGHOST:-localhost}" "$POSTGRES_PORT")/${POSTGRES_DB}?reWriteBatchedInserts=true}"
  export BATCH_PLATFORM_DB_USERNAME="${BATCH_PLATFORM_DB_USERNAME:-$POSTGRES_USER}"
  export BATCH_PLATFORM_DB_PASSWORD="${BATCH_PLATFORM_DB_PASSWORD:-$POSTGRES_PASSWORD}"

  export BATCH_BUSINESS_DB_URL="${BATCH_BUSINESS_DB_URL:-jdbc:postgresql://$(batch_format_host_port "${PGHOST:-localhost}" "$POSTGRES_PORT")/${BUSINESS_DB_NAME}?reWriteBatchedInserts=true}"
  export BATCH_BUSINESS_DB_USERNAME="${BATCH_BUSINESS_DB_USERNAME:-batch_business_writer}"
  export BATCH_BUSINESS_DB_PASSWORD="${BATCH_BUSINESS_DB_PASSWORD:-$POSTGRES_PASSWORD}"

  export BATCH_CONSOLE_PRIMARY_URL="${BATCH_CONSOLE_PRIMARY_URL:-$BATCH_PLATFORM_DB_URL}"
  export BATCH_CONSOLE_PRIMARY_USER="${BATCH_CONSOLE_PRIMARY_USER:-$BATCH_PLATFORM_DB_USERNAME}"
  export BATCH_CONSOLE_PRIMARY_PASSWORD="${BATCH_CONSOLE_PRIMARY_PASSWORD:-$BATCH_PLATFORM_DB_PASSWORD}"
  export BATCH_CONSOLE_REPLICA_USER="${BATCH_CONSOLE_REPLICA_USER:-$BATCH_PLATFORM_DB_USERNAME}"
  export BATCH_CONSOLE_REPLICA_PASSWORD="${BATCH_CONSOLE_REPLICA_PASSWORD:-$BATCH_PLATFORM_DB_PASSWORD}"

  case "${BATCH_CONSOLE_REPLICA_URL:-}" in
    ""|jdbc:postgresql://postgres-replica:*)
      replica_authority="$(batch_format_host_port "${PG_REPLICA_HOST:-localhost}" "${POSTGRES_REPLICA_PORT:-15433}")"
      export BATCH_CONSOLE_REPLICA_URL="jdbc:postgresql://${replica_authority}/${POSTGRES_DB}?reWriteBatchedInserts=true"
      ;;
  esac
}

batch_configure_local_jvm_redis_env() {
  # `.env.local` 同时供 Compose 使用，其中 Redis 采用容器服务名；裸 JVM 必须改用宿主机地址。
  case "${BATCH_REDIS_HOST:-}" in
    ""|redis|valkey)
      export BATCH_REDIS_HOST="localhost"
      ;;
  esac
}

batch_configure_local_jvm_runtime_env() {
  batch_configure_local_jvm_database_env
  batch_configure_local_jvm_redis_env
}

if [[ "${BATCH_ENV_COMMON_HELPERS_ONLY:-0}" != "1" ]]; then
  batch_load_default_env
fi
