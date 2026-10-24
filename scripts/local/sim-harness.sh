#!/usr/bin/env bash
# 用途：— 可重复执行的 sim / 真实数据验证 harness。
# 用法：在仓库根目录执行 bash scripts/local/sim-harness.sh；参数与前置环境见下方说明及所属 README。
# =========================================================
# sim-harness.sh —— 可重复执行的 sim / 真实数据验证 harness。
#
# 子命令:
#   preflight     启动前检查先决条件/环境/配置(fail-fast,不跑业务)
#   reset         数据重制到基线(清运行态 + biz 数据,保留 definition/config/tenant/user)
#   prereq        装配先决条件(biz 表+RLS+只读角色、临时 shard-1、下游 sftp/mockserver、租户导入)
#   verify-data   真实数据验证(两片真实 PG 活体路由,幂等可重复)
#   sim           跑全量 sim 阶段 04→30(前置:preflight + prereq 已过,不跳过阶段)
#   sim-selected  按阶段号定向复验(如 sim-selected 23 25)，明确记录选定范围，不作为全量结果
#   all           preflight → reset → prereq → verify-data → sim 一条龙
#
# 本 harness 固化了若干踩过的问题(见各 check 注释):
#   - 环境文件、SQL 和 JVM JDBC URL 不一致会把同一轮 SIM 拆到多个数据库
#   - sim 阶段需 source scripts/sim/env-common.sh 才有 TRIGGER_BASE/CONSOLE_BASE
#   - 父 shell 中的 env-common 缓存会阻止所选 COMPOSE_ENV_FILE 生效
#   - 00-reset 硬编码 truncate 列表含「后续阶段才建的表」,fresh 库会撞「relation does not exist」
#   - xlsx fixture 过时(job_definition 缺 watermark_field)→ 导入 400
# =========================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=../lib/logging.sh
source "$ROOT/scripts/lib/logging.sh"
# shellcheck source=../lib/process.sh
source "$ROOT/scripts/lib/process.sh"
# 保留父 shell 的污染证据，但固定从当前仓库加载默认环境；否则 set -u 下 PG_CONTAINER
# 等变量会在 harness 尚未执行 preflight 时就导致脚本退出。
INHERITED_BATCH_ENV_COMMON_ROOT="${BATCH_ENV_COMMON_ROOT:-}"
BATCH_ENV_COMMON_ROOT="$ROOT"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"
export COMPOSE_ENV_FILE
# 运行时只能通过 Workflow 启动；此脚本用于准备夹具，不提供兼容逻辑。
# 此前加载的 env-common 不得阻止此 harness 加载
unset BATCH_ENV_LOADED
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
batch_configure_local_jvm_database_env

PG="$PG_CONTAINER"
PGU="$PGUSER"
PLAT_DB="$PLATFORM_DB"
BIZ_DB="$BUSINESS_DB"
CONSOLE_API_PORT="${CONSOLE_API_PORT:-${BATCH_CONSOLE_API_PORT:-$BATCH_DEFAULT_CONSOLE_PORT}}"
TRIGGER_PORT="${TRIGGER_PORT:-${BATCH_TRIGGER_PORT:-$BATCH_DEFAULT_TRIGGER_PORT}}"
ORCHESTRATOR_PORT="${ORCHESTRATOR_PORT:-${BATCH_ORCHESTRATOR_PORT:-$BATCH_DEFAULT_ORCHESTRATOR_PORT}}"
WORKER_IMPORT_PORT="${WORKER_IMPORT_PORT:-${BATCH_WORKER_IMPORT_PORT:-$BATCH_DEFAULT_WORKER_IMPORT_PORT}}"
WORKER_EXPORT_PORT="${WORKER_EXPORT_PORT:-${BATCH_WORKER_EXPORT_PORT:-$BATCH_DEFAULT_WORKER_EXPORT_PORT}}"
WORKER_DISPATCH_PORT="${WORKER_DISPATCH_PORT:-${BATCH_WORKER_DISPATCH_PORT:-$BATCH_DEFAULT_WORKER_DISPATCH_PORT}}"
WORKER_PROCESS_PORT="${WORKER_PROCESS_PORT:-${BATCH_WORKER_PROCESS_PORT:-$BATCH_DEFAULT_WORKER_PROCESS_PORT}}"
WORKER_ATOMIC_PORT="${WORKER_ATOMIC_PORT:-${BATCH_WORKER_ATOMIC_PORT:-$BATCH_DEFAULT_WORKER_ATOMIC_PORT}}"
CONSOLE="${CONSOLE_BASE:-http://localhost:${CONSOLE_API_PORT}}"
SIM_JAVA_OPTS="${SIM_JAVA_OPTS:--Xmx384m -XX:MaxMetaspaceSize=256m}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-batch-kafka}"
MINIO_CONTAINER="${MINIO_CONTAINER:-batch-minio}"
REDIS_CONTAINER="${REDIS_CONTAINER:-batch-valkey}"
BIZ_SHARD_1_CONTAINER="${BIZ_SHARD_1_CONTAINER:-batch-postgres-biz-shard-1}"
SFTP_CONTAINER="${SFTP_CONTAINER:-batch-sim-sftp}"
FIXTURE_DIR="${FIXTURE_DIR:-test-data/tenant-config-packages}"
SIM_LOG_DIR="$(log_run_dir "$ROOT" sim-harness sim-harness)"
HARNESS_TMP_DIR="${HARNESS_TMP_DIR:-$SIM_LOG_DIR/tmp}"
SIM_SQL_DIR="$ROOT/scripts/local/sql"
# shellcheck source=../lib/destructive-ops.sh
source "$ROOT/scripts/lib/destructive-ops.sh"
mkdir -p "$HARNESS_TMP_DIR"
log_link_dir "$ROOT" sim-harness "$SIM_LOG_DIR"

SIM_OWNED_SHARD_SECRETS_DIR=""
if [[ -z "${BIZ_SHARD_SECRETS_DIR:-}" ]]; then
  SIM_OWNED_SHARD_SECRETS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/batch-sim-shard-secrets.XXXXXX")"
  chmod 700 "$SIM_OWNED_SHARD_SECRETS_DIR"
  BIZ_SHARD_SECRETS_DIR="$SIM_OWNED_SHARD_SECRETS_DIR"
fi
export BIZ_SHARD_SECRETS_DIR

cleanup_sim_shard_secrets() {
  if [[ -n "$SIM_OWNED_SHARD_SECRETS_DIR" && -d "$SIM_OWNED_SHARD_SECRETS_DIR" ]]; then
    rm -rf -- "$SIM_OWNED_SHARD_SECRETS_DIR"
    SIM_OWNED_SHARD_SECRETS_DIR=""
  fi
}
trap cleanup_sim_shard_secrets EXIT

cleanup_sim_owned_biz_shard() {
  local shard_ids shard_id cleanup_failed=0
  shard_ids="$(docker ps -aq \
    --filter 'label=io.github.pinpols.batch.testcontainers.owner=file-batch-system' \
    --filter "label=io.github.pinpols.batch.sim.project=${COMPOSE_PROJECT_NAME:-batch-platform}" 2>/dev/null)" || {
      c_red "无法盘点 SIM 自有 biz 分片容器，拒绝报告清理成功"
      return 1
    }
  [[ -n "$shard_ids" ]] || return 0
  batch_require_local_docker_context || return $?

  while IFS= read -r shard_id; do
    [[ -n "$shard_id" ]] || continue
    if docker rm -fv "$shard_id" >/dev/null; then
      c_ylw "  已清理 SIM 自有 biz-shard 容器及其匿名数据卷"
    else
      c_red "清理 SIM 自有 biz-shard 容器失败:${shard_id}"
      cleanup_failed=1
    fi
  done <<< "$shard_ids"
  return "$cleanup_failed"
}

all_sim_teardown() {
  local run_status=$? cleanup_status=0
  trap - EXIT
  cleanup_sim_owned_biz_shard || cleanup_status=$?
  cleanup_sim_shard_secrets || cleanup_status=$?
  if [[ "$run_status" -ne 0 ]]; then
    exit "$run_status"
  fi
  exit "$cleanup_status"
}

c_red()  { printf '\033[0;31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
c_ylw()  { printf '\033[1;33m%s\033[0m\n' "$*"; }
fail()   { c_red "  ✗ $*"; FAILED=1; }
ok()     { c_grn "  ✓ $*"; }
warn()   { c_ylw "  ! $*"; }

sim_platform_sql() {
  local sql_file="$1"
  shift
  local -a docker_env_args=()
  if [[ "$sql_file" == "reset-platform-runtime.sql" ]]; then
    docker_env_args=(-e 'PGOPTIONS=-c batch.destructive_ops=sim-reset')
  fi
  docker exec "${docker_env_args[@]}" -i "$PG" psql -X -v ON_ERROR_STOP=1 -U "$PGU" -d "$PLAT_DB" \
    "$@" -f /dev/stdin < "$SIM_SQL_DIR/$sql_file"
}

sim_business_sql() {
  local sql_file="$1"
  shift
  local -a docker_env_args=()
  if [[ "$sql_file" == "reset-business-runtime.sql" ]]; then
    docker_env_args=(-e 'PGOPTIONS=-c batch.destructive_ops=sim-reset')
  fi
  docker exec "${docker_env_args[@]}" -i "$PG" psql -X -v ON_ERROR_STOP=1 -U "$PGU" -d "$BIZ_DB" \
    "$@" -f /dev/stdin < "$SIM_SQL_DIR/$sql_file"
}

target_database_exists() {
  local database_name="$1"
  docker exec -i "$PG" psql -X -U "$PGU" -d postgres \
    -v ON_ERROR_STOP=1 -v database_name="$database_name" -tA \
    -f /dev/stdin < scripts/db/backup/sql/database-exists.sql
}

# clean-at-START(幂等):上一轮被 kill -9 / IDE 强停时 EXIT trap 不触发,孤儿 testcontainers
# 与 biz-shard 残留;每次开跑先自净,不用人工去 docker rm。只清残留,不碰受管 dev 栈。
clean_stale_env() {
  [ -x scripts/local/clean-stale-test-env.sh ] || [ -f scripts/local/clean-stale-test-env.sh ] || return 0
  echo "== 清理上一轮本脚本负责的残留(幂等)=="
  bash scripts/local/clean-stale-test-env.sh || {
    c_red "  ✗ 残留清理未完成，停止 SIM"
    return 1
  }
}

# ---------------------------------------------------------
# preflight:启动前检查(不依赖 app 在跑)。FAILED=1 → 退 1。
# ---------------------------------------------------------
preflight() {
  FAILED=0
  echo "== preflight:环境 / 配置 =="
  [[ -f "$COMPOSE_ENV_FILE" ]] && ok "环境文件已选定: $COMPOSE_ENV_FILE" || fail "环境文件不存在: $COMPOSE_ENV_FILE"

  [[ "$BATCH_ENV_COMMON_ROOT" == "$ROOT" && "${BATCH_ENV_LOADED:-}" == 1 ]] \
    && ok "已从本仓库加载所选环境(忽略父 shell 的 env-common 缓存)" \
    || fail "本仓库环境未按所选文件加载"
  if [[ -n "$INHERITED_BATCH_ENV_COMMON_ROOT" ]]; then
    warn "父 shell 曾设置 BATCH_ENV_COMMON_ROOT；本 harness 已重置为当前仓库路径"
  fi

  # bypass-mode:sim curl 脚本不带 X-XSRF-TOKEN,bypass=false 时 CSRF 拦所有写(403)。sim 必须 true。
  local bypass
  bypass="${BATCH_SECURITY_BYPASS_MODE:-}"
  if [[ "$bypass" == "true" ]]; then
    ok "BATCH_SECURITY_BYPASS_MODE=true(CSRF 对 /** 放行,sim 写可过)"
  else
    fail "BATCH_SECURITY_BYPASS_MODE=${bypass:-<空>} —— sim 必须 true,否则 CSRF 拦所有写请求(403)"
  fi

  if ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
       source scripts/sim/env-common.sh
       sim_validate_infrastructure_targets ); then
    ok "应用/SDK Kafka 与 SFTP 端点绑定所选 Compose 项目"
  else
    fail "基础设施归属或端点不一致；拒绝继续 SIM"
    return 1
  fi

  echo "== preflight:数据库目标一致性 =="
  local platform_url_db business_url_db
  platform_url_db="${BATCH_PLATFORM_DB_URL##*/}"
  platform_url_db="${platform_url_db%%\?*}"
  business_url_db="${BATCH_BUSINESS_DB_URL##*/}"
  business_url_db="${business_url_db%%\?*}"
  if [[ "$PLAT_DB" == "$POSTGRES_DB" && "$PLAT_DB" == "$PGDATABASE" \
      && "$BIZ_DB" == "$BUSINESS_DB_NAME" && "$platform_url_db" == "$PLAT_DB" \
      && "$business_url_db" == "$BIZ_DB" ]]; then
    ok "平台/业务 SQL 与 JVM JDBC URL 指向同一组数据库"
  else
    fail "数据库目标不一致: PLATFORM_DB=$PLAT_DB POSTGRES_DB=$POSTGRES_DB PGDATABASE=$PGDATABASE BATCH_PLATFORM_DB_URL_DB=$platform_url_db BUSINESS_DB=$BIZ_DB BUSINESS_DB_NAME=$BUSINESS_DB_NAME BATCH_BUSINESS_DB_URL_DB=$business_url_db"
  fi
  local database_name database_exists
  for database_name in "$PLAT_DB" "$BIZ_DB"; do
    if ! database_exists=$(target_database_exists "$database_name" 2>/dev/null); then
      fail "无法通过 PostgreSQL maintenance DB 检查目标库 ${database_name}；检查 PG 容器和账号"
    elif [[ "$database_exists" == 1 ]]; then
      ok "目标数据库存在: $database_name"
    else
      fail "目标数据库不存在: ${database_name}；SIM 不会自动创建或覆盖数据库"
    fi
  done

  echo "== preflight:基础设施 =="
  local svc
  for svc in "$PG" "$KAFKA_CONTAINER" "$MINIO_CONTAINER" "$REDIS_CONTAINER"; do
    local st
    st=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo missing)
    [[ "$st" == healthy ]] && ok "$svc healthy" || fail "$svc 状态=$st(需 docker compose up infra)"
  done
  local minio_network
  minio_network=$(docker inspect -f '{{range $name, $network := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
    "$MINIO_CONTAINER" 2>/dev/null | head -n 1 || true)
  if [[ "${MINIO_MC_ENDPOINT:-http://minio:9000}" != "http://minio:9000" ]]; then
    fail "SIM MinIO 清理只允许当前本地 Compose 网络服务 minio:9000"
  elif [[ -z "$minio_network" ]]; then
    fail "无法读取本地 MinIO 容器的 Docker 网络"
  elif [[ -n "${MINIO_MC_NETWORK:-}" && "$MINIO_MC_NETWORK" != "$minio_network" ]]; then
    fail "MINIO_MC_NETWORK 不属于当前 MinIO 容器网络"
  else
    ok "MinIO 清理目标绑定本机容器及其 Compose 网络"
  fi

  echo "== preflight:构建产物 / secrets / fixtures =="
  local n
  n=$(find build/runtime-jars -maxdepth 1 -type f -name '*.jar' 2>/dev/null | wc -l | tr -d ' ')
  [[ "$n" == 8 ]] && ok "runtime-jars 8 个" || fail "runtime-jars=$n(需 build-apps.sh,期望 8)"

  [[ -x scripts/local/provision-biz-shard.sh && -x scripts/local/verify-biz-shard.sh ]] \
    && ok "分片校验脚本就绪(凭据将在本次运行临时生成)" \
    || fail "分片校验脚本缺失或不可执行"

  local t
  for t in ta tb tc; do
    [[ -f "$FIXTURE_DIR/${t}-tenant-config-package-test.xlsx" ]] \
      && ok "fixture $t 存在" || fail "fixture $t 缺失:$FIXTURE_DIR/${t}-tenant-config-package-test.xlsx"
  done
  if "${PYTHON_BIN:-python3}" scripts/fix-fixture-xlsx.py --check >/tmp/sim-fixture-xlsx-check.log 2>&1; then
    ok "tenant-package xlsx fixture header 与当前 12 sheet schema 对齐"
  else
    fail "tenant-package xlsx fixture 过时或 openpyxl 不可用:$(tail -20 /tmp/sim-fixture-xlsx-check.log | tr '\n' ' ')"
  fi

  echo "== preflight:sim env(TRIGGER_BASE 等)=="
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
    source scripts/sim/env-common.sh >/dev/null 2>&1
    [[ -n "${TRIGGER_BASE:-}" && -n "${CONSOLE_BASE:-}" ]] ) \
    && ok "env-common 可 source(TRIGGER_BASE/CONSOLE_BASE 解析)" \
    || fail "env-common source 后 TRIGGER_BASE/CONSOLE_BASE 为空"

  # app 在跑 → 追加运行期检查(健康 + fixture 兼容性 dry-upload)
  if curl -s -o /dev/null --max-time 3 "$CONSOLE/actuator/health" 2>/dev/null; then
    echo "== preflight:运行期(app 已起)=="
    local p runtime_ready=1
    for p in "$CONSOLE_API_PORT" "$TRIGGER_PORT" "$ORCHESTRATOR_PORT" "$WORKER_IMPORT_PORT" "$WORKER_EXPORT_PORT" "$WORKER_DISPATCH_PORT" "$WORKER_PROCESS_PORT" "$WORKER_ATOMIC_PORT"; do
      local hc health_url
      health_url="http://localhost:$p/actuator/health"
      if ! hc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$health_url" 2>/dev/null); then
        hc="000"
      fi
      if [[ "$hc" == 200 ]]; then
        ok "svc :$p UP"
      else
        fail "svc :$p health=$hc"
        runtime_ready=0
      fi
    done
    if [[ "$runtime_ready" == 1 ]]; then
      if ensure_worker_registrations 30 2; then
        ok "所选平台库中的五类 Worker 均有新鲜 ONLINE 心跳"
        fixture_compat_check || true
      else
        fail "所选数据库中 Worker 心跳不完整；检查 COMPOSE_ENV_FILE 是否与活动服务一致"
      fi
    else
      warn "运行期服务不完整，跳过 fixture dry-upload"
    fi
  else
    warn "app 未在跑 → 跳过运行期检查(启动后再 preflight 验 fixture 兼容性)"
  fi

  echo
  [[ "${FAILED:-0}" == 0 ]] && { c_grn "== preflight 全过 =="; return 0; } || { c_red "== preflight 有失败项,先修再起 =="; return 1; }
}

# fixture 兼容性:登录后 dry-upload ta fixture,检测「missing required headers」(如 watermark_field)。
fixture_compat_check() {
  local cj; cj=$(mktemp)
  curl -s -o /dev/null -c "$cj" --max-time 10 -X POST "$CONSOLE/api/console/auth/login" \
    -H 'Content-Type: application/json' --data-raw '{"username":"admin","password":"admin123"}' || true
  local body; body=$(curl -s --max-time 20 -b "$cj" \
    -H 'X-Tenant-Id: ta' -H 'Idempotency-Key: preflight-dry' -H 'X-Request-Id: preflight-dry' \
    -F "file=@$FIXTURE_DIR/ta-tenant-config-package-test.xlsx" \
    "$CONSOLE/api/console/config/tenant-package/excel/upload?tenantId=ta" 2>/dev/null || true)
  if echo "$body" | grep -q 'missing required headers'; then
    fail "fixture 与 import schema 不兼容:$(echo "$body" | grep -oE 'missing required headers: \[[^]]*\]' | head -1) —— 需更新 xlsx fixture"
  elif echo "$body" | grep -qE '"uploadToken"|FORBIDDEN'; then
    [[ "$body" == *uploadToken* ]] && ok "fixture 兼容(dry-upload 拿到 token)" || fail "dry-upload 403(检查 bypass-mode)"
  else
    warn "dry-upload 结果未判定:$(echo "$body" | head -c 120)"
  fi
}

# ---------------------------------------------------------
# reset:数据重制到基线(robust:只 truncate 实际存在的表,修 00-reset 撞缺表的问题)
# ---------------------------------------------------------
reset() {
  echo "== reset:运行态 + biz 数据回基线(保留 definition/config/tenant/user)=="
  batch_require_compose_container "$PG" || return $?
  # 与 00-reset 共用平台 SQL；使用 harness 启动时解析的唯一环境快照。
  # biz 数据:动态枚举 biz 现有基表(非分区子表)TRUNCATE CASCADE,避免硬编码撞缺表
  if sim_business_sql reset-business-runtime.sql >/dev/null; then
    ok "biz 数据已清(动态枚举,跳过不存在的表)"
  else
    c_red "  ✗ biz 清理失败"
    return 1
  fi

  # 平台运行态 truncate(job_/pipeline_/workflow_/retry/outbox/file_record 等),保留
  # *_definition/config/tenant/user/系统表。retry_schedule / worker_report_outbox 不会被
  # job_instance 的 CASCADE 一并清理；若漏清，历史 WAITING retry 会在全量 sim 期间持续抢
  # scheduler，拖慢后续场景的终态落库并造成假性超时。
  if sim_platform_sql reset-platform-runtime.sql >/dev/null; then
    ok "平台运行态已清(保留定义/配置/租户/用户/系统表)"
  else
    c_red "  ✗ 平台运行态清理失败"
    return 1
  fi
  c_grn "== reset 完成 =="
}

# all 的 reset 必须在控制面静默时执行。应用保持运行会在 TRUNCATE CASCADE
# 获取表锁的同时继续写 job/outbox，导致清理事务之间互相等待并产生死锁。
# 先停止 Compose 管理的应用容器；host 模式再停止宿主 JVM。基础设施、数据卷
# 和配置不受影响，后续 ensure_core_runtime 会按调用方指定的运行模式恢复控制面。
quiesce_runtime_for_reset() {
  local compose_env="${COMPOSE_ENV_FILE:-$ROOT/.env.local}"
  local compose_app="$ROOT/deploy/docker/compose/app.yml"
  [[ -f "$compose_app" ]] || { c_red "  ✗ 未找到 Compose 应用文件"; return 1; }
  local compose_args=(docker compose)
  [[ -f "$compose_env" ]] && compose_args+=(--env-file "$compose_env")
  compose_args+=(-f "$ROOT/docker-compose.yml" -f "$compose_app" stop
    console-api trigger orchestrator worker-import worker-export worker-process worker-dispatch worker-atomic)

  if docker info >/dev/null 2>&1 && [[ "${BATCH_DEPLOY_MODE:-container}" == "container" ]]; then
    echo "== reset 前暂停应用写入(保留基础设施) =="
    "${compose_args[@]}" >/dev/null
  fi
  if [[ "${BATCH_SCRIPT_RUNTIME:-auto}" == "host" ]]; then
    STOP_WAIT_SEC="${STOP_WAIT_SEC:-5}" bash scripts/local/stop-all.sh >/dev/null
  fi
  local port
  for port in "$CONSOLE_API_PORT" "$TRIGGER_PORT" "$ORCHESTRATOR_PORT" \
      "$WORKER_IMPORT_PORT" "$WORKER_EXPORT_PORT" "$WORKER_DISPATCH_PORT" \
      "$WORKER_PROCESS_PORT" "$WORKER_ATOMIC_PORT"; do
    if process_port_is_listening "$port"; then
      echo "✗ :$port 仍有进程监听；拒绝在有写入者时 reset" >&2
      return 1
    fi
  done
}

# ---------------------------------------------------------
# prereq:幂等装配先决条件
# ---------------------------------------------------------
prereq() {
  echo "== prereq:biz 表 + RLS + 只读角色 =="
  bash scripts/sim/01-init-biz.sh >"$SIM_LOG_DIR/initbiz.log" 2>&1 && ok "01-init-biz" || { c_red "  ✗ 01-init-biz(见 $SIM_LOG_DIR/initbiz.log)"; return 1; }
  # biz 角色密码本地注入(SQL 脚本不再含默认密码);本地复用本地 DB 密码。
  local biz_pw="${POSTGRES_PASSWORD:-$BATCH_DEFAULT_POSTGRES_PASSWORD}"
  docker exec -i "$PG" psql -q -U "$PGU" -d "$BIZ_DB" \
    -v writer_password="$biz_pw" -v admin_password="$biz_pw" \
    < scripts/db/business/rls-phase-a.sql >/dev/null 2>&1 && ok "rls-phase-a" \
    || { c_red "  ✗ rls-phase-a(业务库 SIM 安全策略未就绪)"; return 1; }
  docker exec -i "$PG" psql -q -U "$PGU" -d "$BIZ_DB" \
    -v readonly_password="$biz_pw" -v readonly_all_password="$biz_pw" \
    < scripts/db/business/diagnostic-readonly-role.sql >/dev/null 2>&1 && ok "只读角色" \
    || { c_red "  ✗ diagnostic-readonly-role(业务库权限基线未就绪)"; return 1; }

  echo "== prereq:原子任务 job + refresh_metrics procedure =="
  # Stage 07/16/21 只需要四个 atomic job 和示例 procedure。不要在已有环境重放整份
  # platform_seed.sql；其固定 ID 可能与租户导入产生的自然键记录不同并触发唯统一冲突。
  sim_platform_sql ensure-sim-atomic-prerequisites.sql -q >"$SIM_LOG_DIR/atomic-prerequisites.log" 2>&1 \
    && ok "atomic prerequisites" \
    || { c_red "  ✗ atomic prerequisites(见 $SIM_LOG_DIR/atomic-prerequisites.log)"; return 1; }

  # 清理已经过期的 WAITING 样例，避免历史数据被 scheduler 反复重派并污染场景时序。
  sim_platform_sql delete-expired-waiting-retries.sql -q >/dev/null \
    && ok "清理 platform_seed 过期 retry 样例" \
    || { c_red "  ✗ platform_seed 过期 retry 清理失败"; return 1; }

  echo "== prereq:shard-1(幂等)=="
  BATCH_SIM_RESIDUE_CONTAINER=true \
    bash scripts/local/provision-biz-shard.sh shard-1 "${BIZ_SHARD_1_PORT:-15442}" >"$SIM_LOG_DIR/shard1.log" 2>&1 \
    && ok "shard-1 就绪" \
    || { c_red "  ✗ shard-1(见 $SIM_LOG_DIR/shard1.log)"; cleanup_sim_owned_biz_shard || c_red "临时 shard 清理失败"; return 1; }

  echo "== prereq:下游模拟器(sftp/mockserver)=="
  if [[ "${SIM_SIMULATORS_READY:-0}" != 1 ]]; then
    bash scripts/sim/02-start-sim.sh >"$SIM_LOG_DIR/sim02.log" 2>&1 \
      && ok "sftp/mockserver" \
      || { c_red "  ✗ 02-start-sim(见 $SIM_LOG_DIR/sim02.log)"; cleanup_sim_owned_biz_shard || c_red "临时 shard 清理失败"; return 1; }
  fi

  echo "== prereq:租户导入(03)=="
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    bash scripts/sim/03-import-tenants.sh ) >"$SIM_LOG_DIR/import-tenants.log" 2>&1 \
    && ok "租户配置导入" \
    || { c_red "  ✗ 03-import 失败(见 $SIM_LOG_DIR/import-tenants.log)"; cleanup_sim_owned_biz_shard || c_red "临时 shard 清理失败"; return 1; }
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    bash scripts/sim/03-import-tenants.sh ) >"$SIM_LOG_DIR/import-idempotency-check.log" 2>&1 \
    && ok "配置包重复运行验证(未重复 Apply)" \
    || { c_red "  ✗ 配置包重复运行验证失败(见 $SIM_LOG_DIR/import-idempotency-check.log)"; cleanup_sim_owned_biz_shard || c_red "临时 shard 清理失败"; return 1; }
  c_grn "== prereq 完成 =="
}

# ---------------------------------------------------------
# verify-data:真实数据路由验证(已幂等)
# ---------------------------------------------------------
verify_data() {
  echo "== verify-data:两片真实 PG 活体路由 =="
  local rc=0 cleanup_rc=0 secrets_cleanup_rc=0
  bash scripts/local/verify-biz-shard.sh || rc=$?
  cleanup_sim_owned_biz_shard || cleanup_rc=$?
  cleanup_sim_shard_secrets || secrets_cleanup_rc=$?
  if [[ "$rc" -ne 0 ]]; then
    [[ "$cleanup_rc" -eq 0 && "$secrets_cleanup_rc" -eq 0 ]] \
      || c_red "清理失败；保留原验证错误码=$rc"
    return "$rc"
  fi
  [[ "$cleanup_rc" -eq 0 ]] || return "$cleanup_rc"
  [[ "$secrets_cleanup_rc" -eq 0 ]] || return "$secrets_cleanup_rc"
  return "$rc"
}

all() {
  trap all_sim_teardown EXIT
  preflight && clean_stale_env && quiesce_runtime_for_reset && reset \
    && prepare_sim_inputs && ensure_core_runtime && prereq && verify_data && sim
}

sim_minio_find_inputs() {
  local target="$1" generated_name_regex="$2" output
  if output=$(bash scripts/lib/minio-mc.sh find "$target" --regex "$generated_name_regex" --print '{}' 2>&1); then
    printf '%s' "$output"
    return 0
  fi
  # 明确选定的环境文件（该文件可能与 .env.local 隔离）。
  # MinIO 使用前缀组织对象：最后一个对象移除后，find 会将不存在的前缀报告为
  if [[ "$output" != *$'\n'* && "$output" == *"Unable to stat "* \
      && ( "$output" == *"Object does not exist." \
           || ( "$output" == *"Bucket "* && "$output" == *" does not exist." ) ) ]]; then
    return 0
  fi
  printf '%s\n' "$output" >&2
  return 1
}

# Stage 25/26 的 SIM 入站对象会留在持久 MinIO bucket 中；只按脚本生成的命名规则清理。
clean_sim_minio_inputs() {
  local target="local/$MINIO_BUCKET/ingress/ta"
  local generated_name_regex='^(bundle-[ab]-[0-9]{8}-[0-9]+\.csv|bundle-import-[0-9]+\.batch\.json|stage2e-checkpoint/sim-stage2e-ckpt-[0-9]+\.xml)$'
  local cleanup_phase="${1:-pre-run}"
  local cleanup_log="$SIM_LOG_DIR/clean-sim-minio-inputs-${cleanup_phase}.log"

  # 只删 Stage 25/26 明确生成的随机名输入；batch-dev 其余对象和出站产物保留。
  local inventory
  if ! inventory=$(sim_minio_find_inputs "$target" "$generated_name_regex" 2>>"$cleanup_log"); then
    c_red "  ✗ MinIO SIM 输入盘点失败(见 $cleanup_log)"
    return 1
  fi
  printf '%s\n' "$inventory" >"$cleanup_log"
  local inventoried
  inventoried=$(printf '%s\n' "$inventory" | awk 'NF { count++ } END { print count + 0 }')
  if [[ -n "$inventory" ]] && ! bash scripts/lib/minio-mc.sh find "$target" --regex "$generated_name_regex" \
      --exec 'mc rm --force {}' >> "$cleanup_log" 2>&1; then
    c_red "  ✗ MinIO SIM 输入清理失败(见 $cleanup_log)"
    return 1
  fi
  local remaining
  if ! remaining=$(sim_minio_find_inputs "$target" "$generated_name_regex" 2>>"$cleanup_log"); then
    c_red "  ✗ MinIO SIM 输入清理后复核失败(见 $cleanup_log)"
    return 1
  fi
  if [[ -n "$remaining" ]]; then
    printf '%s\n' "$remaining" >> "$cleanup_log"
    c_red "  ✗ MinIO 仍有 SIM 输入残留(见 $cleanup_log)"
    return 1
  fi
  ok "MinIO 精确清理并复核 SIM ingress 输入(移除 $inventoried 个；详情 $cleanup_log)"
}

# SIM 的 SFTP 卷会跨运行保留。只移除本模拟器生成的三种 inbound 文件，且在
# worker 启动前执行；其他文件和 SFTP/MockServer/MinIO 持久卷保持原样。
clean_sim_sftp_inputs() {
  local cleanup_log="$SIM_LOG_DIR/clean-sim-inputs.log"
  local generated_path_regex='^(/home/ta/inbound/ta-customer-[0-9]{4}-[0-9]{2}-[0-9]{2}|/home/tb/inbound/tb-transaction-[0-9]{4}-[0-9]{2}-[0-9]{2}|/home/tc/inbound/tc-risk-score-[0-9]{4}-[0-9]{2}-[0-9]{2})\.csv$'
  local inventory remaining input inventoried=0

  if ! inventory=$(docker exec "$SFTP_CONTAINER" sh -c '
    set -eu
    find /home/ta/inbound -maxdepth 1 -type f -name "ta-customer-*.csv" -print
    find /home/tb/inbound -maxdepth 1 -type f -name "tb-transaction-*.csv" -print
    find /home/tc/inbound -maxdepth 1 -type f -name "tc-risk-score-*.csv" -print
  '); then
    c_red "  ✗ SFTP SIM 输入盘点失败(见 $cleanup_log)"
    return 1
  fi
  while IFS= read -r input; do
    [[ -n "$input" ]] || continue
    if [[ ! "$input" =~ $generated_path_regex ]]; then
      printf 'unexpected SFTP SIM input path: %s\n' "$input" >>"$cleanup_log"
      c_red "  ✗ SFTP SIM 输入包含未认可路径(见 $cleanup_log)"
      return 1
    fi
    inventoried=$((inventoried + 1))
  done <<<"$inventory"
  printf '%s\n' "$inventory" >"$cleanup_log"
  if ! docker exec "$SFTP_CONTAINER" sh -c '
    set -eu
    find /home/ta/inbound /home/tb/inbound /home/tc/inbound -maxdepth 1 -type f \
      \( -path "/home/ta/inbound/ta-customer-*.csv" \
         -o -path "/home/tb/inbound/tb-transaction-*.csv" \
         -o -path "/home/tc/inbound/tc-risk-score-*.csv" \) -print -delete
  ' >>"$cleanup_log" 2>&1; then
    c_red "  ✗ SFTP SIM 输入清理失败(见 $cleanup_log)"
    return 1
  fi
  if ! remaining=$(docker exec "$SFTP_CONTAINER" sh -c '
    set -eu
    find /home/ta/inbound -maxdepth 1 -type f -name "ta-customer-*.csv" -print
    find /home/tb/inbound -maxdepth 1 -type f -name "tb-transaction-*.csv" -print
    find /home/tc/inbound -maxdepth 1 -type f -name "tc-risk-score-*.csv" -print
  '); then
    c_red "  ✗ SFTP SIM 输入清理后复核失败(见 $cleanup_log)"
    return 1
  fi
  if [[ -n "$remaining" ]]; then
    printf 'residual %s\n' "$remaining" >>"$cleanup_log"
    c_red "  ✗ SFTP 仍有 SIM 入站文件残留(见 $cleanup_log)"
    return 1
  fi
  ok "SFTP 精确清理并复核 SIM inbound 输入(移除 $inventoried 个；详情 $cleanup_log)"
}

cleanup_sim_inputs() {
  local port
  for port in "$CONSOLE_API_PORT" "$TRIGGER_PORT" "$ORCHESTRATOR_PORT" \
      "$WORKER_IMPORT_PORT" "$WORKER_EXPORT_PORT" "$WORKER_DISPATCH_PORT" \
      "$WORKER_PROCESS_PORT" "$WORKER_ATOMIC_PORT"; do
    if process_port_is_listening "$port"; then
      c_red "  ✗ :$port 仍有应用监听；拒绝删除 SIM 输入"
      return 1
    fi
  done
  clean_sim_sftp_inputs || return 1
  clean_sim_minio_inputs post-run
}

prepare_sim_inputs() {
  sim_platform_sql disable-stage5b-fault-channel.sql >"$SIM_LOG_DIR/clean-sim-fault-channels.log" 2>&1 \
    || { c_red "  ✗ SIM 故障配置恢复未完成"; return 1; }
  echo "== all:启动独立模拟器并清理旧 SIM 入站文件(应用尚未启动) =="
  bash scripts/sim/02-start-sim.sh >"$SIM_LOG_DIR/sim02.log" 2>&1 \
    || { c_red "  ✗ 02-start-sim(见 $SIM_LOG_DIR/sim02.log)"; return 1; }
  clean_sim_sftp_inputs || return 1
  clean_sim_minio_inputs pre-run || return 1
  SIM_SIMULATORS_READY=1
  export SIM_SIMULATORS_READY
  ok "只清理 ta/tb/tc 的 SIM 命名源文件；其他 SFTP 数据保留"
}

# sim 会长时间跑多阶段,本地 Docker/IDE/浏览器共存时 Java 进程可能被系统回收。
# 每个阶段前做一次轻量健康检查;若核心服务不健康,用本地小堆参数拉起,避免后续
# import worker 只看到 Kafka 但注册不到 orchestrator 而空转。
ensure_core_runtime() {
  local ports=("$ORCHESTRATOR_PORT" "$CONSOLE_API_PORT" "$TRIGGER_PORT" "$WORKER_IMPORT_PORT" "$WORKER_EXPORT_PORT" "$WORKER_DISPATCH_PORT" "$WORKER_PROCESS_PORT" "$WORKER_ATOMIC_PORT")
  local p code unhealthy=0
  for p in "${ports[@]}"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$p/actuator/health" 2>/dev/null || true)
    [[ "$code" == 200 ]] || { unhealthy=1; break; }
  done
  [[ "$unhealthy" == 0 ]] && return 0

  local log
  log="$SIM_LOG_DIR/restart-core-$(date +%Y%m%d%H%M%S).log"
  echo "  [core-runtime] unhealthy(:$p health=${code:-000}), restart core services → $log"
  # 下方重启的是宿主机 JVM，因此启动前需要映射宿主机可访问的地址。
  batch_configure_local_jvm_runtime_env
  JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS}" SKIP_CDS=1 \
    bash scripts/local/restart.sh trigger orchestrator console worker-import worker-export worker-process worker-dispatch worker-atomic >"$log" 2>&1
  for p in "${ports[@]}"; do
    for _ in $(seq 1 90); do
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$p/actuator/health" 2>/dev/null || true)
      [[ "$code" == 200 ]] && break
      sleep 2
    done
    [[ "$code" == 200 ]] || { echo "  [core-runtime] :$p NOT ready(health=$code), see $log" >&2; return 1; }
  done
}

# 健康探针只代表 HTTP 进程已启动，不能证明 worker 已注册并能被调度器选中。
# 全量 sim 在高负载/重启窗口内若直接发任务，会把“worker 尚未就绪”误报成业务终态超时。
ensure_worker_registrations() {
  local groups=(IMPORT EXPORT PROCESS DISPATCH ATOMIC)
  local timeout_seconds="${1:-90}" poll_interval_seconds="${2:-2}"
  if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ && "$poll_interval_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo "  [worker-runtime] timeout 和 poll interval 必须为正整数" >&2
    return 2
  fi
  local group count elapsed=0 missing_groups
  while :; do
    missing_groups=""
    for group in "${groups[@]}"; do
      count=$(sim_platform_sql count-recent-online-workers.sql -q -tA \
        -v worker_group="$group" -v heartbeat_window_seconds=90 \
        2>/dev/null | tr -d '[:space:]' || true)
      if ! [[ "${count:-0}" =~ ^[1-9][0-9]*$ ]]; then
        missing_groups+="${group}=${count:-0} "
      fi
    done
    if [[ -z "$missing_groups" ]]; then
      return 0
    fi
    if (( elapsed >= timeout_seconds )); then
      echo "  [worker-runtime] 所选平台库 worker 未注册或心跳已过期(timeout=${timeout_seconds}s, missing=${missing_groups% })" >&2
      return 1
    fi
    sleep "$poll_interval_seconds"
    elapsed=$((elapsed + poll_interval_seconds))
  done
}

# ---------------------------------------------------------
# sim:全量阶段 04→30
# restart_import:为特定 stage 切换 worker-import 互斥配置。
#   default    = checkpoint off + no skip(17 PARTITION_REPLACE_COPY 需 checkpoint=false)
#                注:checkpoint 默认值已在 P0 翻 true,故 default 基线必须**显式** -D=false,
#                否则 17 会撞"PARTITION_REPLACE_COPY 与续跑开关互斥"拒跑。
#   skip       = skip-profile(23 import-stage2d 的 skip 阈值场景)
#   checkpoint = checkpoint=true(25 import-stage2e 真实崩溃续跑)
#   bundle     = batch-manifest + arrival(26 文件束导入全链)
# 用 build/runtime-jars(与 start-all 一致);env 由调用方子 shell 的 source env-common 提供。
restart_import() {
  local mode="${1:-default}" extra="-Dbatch.worker.checkpoint.enabled=false"
  case "$mode" in
    skip) extra="-Dbatch.worker.checkpoint.enabled=false -Dbatch.worker.import.skip.enabled=true -Dbatch.worker.import.skip.threshold-mode=ABSOLUTE -Dbatch.worker.import.skip.max-skip-count=1 -Dbatch.worker.import.skip.error-sink-type=ERROR_TABLE" ;;
    checkpoint) extra="-Dbatch.worker.checkpoint.enabled=true" ;;
    bundle) extra="-Dbatch.worker.checkpoint.enabled=false -Dbatch.worker.import.scanner.batch-manifest-enabled=true -Dbatch.worker.import.scanner.arrival.enabled=true -Dbatch.worker.import.scanner.stability-window-seconds=0 -Dbatch.worker.import.scanner.poll-interval-millis=2000 -Dbatch.worker.import.scanner.biz-date-pattern=(?<bizDate>[0-9]{8})" ;;
  esac
  if [[ "${BATCH_SCRIPT_RUNTIME:-auto}" == "host" ]]; then
    # restart.sh 接收 JAVA_OPTS 后会重新加载 COMPOSE_ENV_FILE。SIM
    # 调用若返回明确的目标不存在响应才按空结果处理；认证或网络错误仍须终止。
    # 该文件包含基准 JAVA_OPTS，会清除当前模式所需的参数。
    COMPOSE_ENV_FILE=/dev/null \
    JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS} $extra" \
      bash scripts/local/restart.sh worker-import >"$SIM_LOG_DIR/worker-import-${mode}.log" 2>&1
    for _ in $(seq 1 40); do
      curl -s -o /dev/null -w '%{http_code}' "http://localhost:${WORKER_IMPORT_PORT}/actuator/health" 2>/dev/null \
        | grep -F 200 >/dev/null && { echo "  [worker-import:$mode ready]"; return 0; }
      sleep 3
    done
    echo "  [worker-import:$mode NOT ready] 见 $SIM_LOG_DIR/worker-import-${mode}.log" >&2
    return 1
  fi
  # 容器模式不能按 Worker host port 查 PID；Docker 端口代理被误杀会连带终止 Docker
  # daemon。restart.sh 会检测受管容器并改用 Compose 重建指定服务。
  local compose_service
  compose_service="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.service" }}' batch-worker-import 2>/dev/null || true)"
  if [[ "${BATCH_SCRIPT_RUNTIME:-auto}" != "host" && "$compose_service" == "worker-import" ]]; then
    local checkpoint_enabled=false skip_enabled=false skip_max=0 error_sink=BOTH
    if [[ "$mode" == checkpoint ]]; then
      checkpoint_enabled=true
    elif [[ "$mode" == skip ]]; then
      skip_enabled=true
      skip_max=1
      error_sink=ERROR_TABLE
    fi
    COMPOSE_ENV_FILE=/dev/null \
    BATCH_WORKER_CHECKPOINT_ENABLED="$checkpoint_enabled" \
    BATCH_WORKER_IMPORT_SKIP_ENABLED="$skip_enabled" \
    BATCH_WORKER_IMPORT_SKIP_THRESHOLD_MODE=ABSOLUTE \
    BATCH_WORKER_IMPORT_SKIP_MAX_SKIP_COUNT="$skip_max" \
    BATCH_WORKER_IMPORT_ERROR_SINK_TYPE="$error_sink" \
    JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS} $extra" \
      bash scripts/local/restart.sh worker-import
    return $?
  fi
  local pid; pid="$(process_first_listen_pid "$WORKER_IMPORT_PORT")"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  fi
  local java_opts="${JAVA_OPTS:-$SIM_JAVA_OPTS}"
  # shellcheck disable=SC2086
  nohup java --enable-native-access=ALL-UNNAMED -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off $java_opts $extra \
    -jar build/runtime-jars/worker-import.jar --spring.profiles.active=local >"$SIM_LOG_DIR/worker-import-${mode}.log" 2>&1 &
  disown
  for _ in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}' "http://localhost:${WORKER_IMPORT_PORT}/actuator/health" 2>/dev/null \
      | grep -F 200 >/dev/null && { echo "  [worker-import:$mode ready]"; return 0; }
    sleep 3
  done
  echo "  [worker-import:$mode NOT ready] 见 $SIM_LOG_DIR/worker-import-${mode}.log" >&2; return 1
}

# 27 需要真实命中批量 listener。PROCESS 默认 max-poll-records=1 且并发为 4，
# 只翻 enabled 仍会退化为 K=1；这里把独占 sim 窗口规整为单 listener、每 poll 5 条，
# 同时满足启动期背压守卫。阶段结束后恢复模块默认值。
restart_process_batch_claim() {
  local mode="${1:-default}"
  local extra="-Dbatch.worker.batch-claim.enabled=false"
  if [[ "$mode" == enabled ]]; then
    extra="-Dbatch.worker.batch-claim.enabled=true -Dbatch.worker.batch-claim.max-batch-size=5 -Dspring.kafka.listener.concurrency=1 -Dspring.kafka.consumer.max-poll-records=5 -Dspring.kafka.consumer.fetch-max-wait=2000 -Dbatch.worker.max-concurrent-tasks=5 -Dbatch.worker.execution.pool-size=5"
  fi
  # 父进程已加载并导出选定的环境快照。
  # 防止 restart.sh 重新加载基准 JAVA_OPTS 并覆盖 SIM 配置。
  COMPOSE_ENV_FILE=/dev/null \
  JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS} $extra" SKIP_CDS=1 \
    bash scripts/local/restart.sh worker-process >"$SIM_LOG_DIR/worker-process-batch-claim-${mode}.log" 2>&1
  for _ in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}' "http://localhost:${WORKER_PROCESS_PORT}/actuator/health" 2>/dev/null \
      | grep -F 200 >/dev/null && { echo "  [worker-process:batch-claim-$mode ready]"; return 0; }
    sleep 3
  done
  echo "  [worker-process:batch-claim-$mode NOT ready] 见 $SIM_LOG_DIR/worker-process-batch-claim-${mode}.log" >&2
  return 1
}

# Stage 21 必须验证 Atomic HTTP 执行器的私有地址围栏。
# 本地开发 profile 默认关闭该围栏，因此仅在本隔离 SIM 窗口临时启用，
# 并在场景结束后立即恢复本地默认值。
restart_atomic_http_private_ip_guard() {
  local mode="${1:-default}" extra=""
  if [[ "$mode" == enabled ]]; then
    extra="-Dbatch.worker.executors.http.block-private-ips=true"
  fi
  COMPOSE_ENV_FILE=/dev/null \
  JAVA_OPTS="${JAVA_OPTS:-$SIM_JAVA_OPTS} $extra" SKIP_CDS=1 \
    bash scripts/local/restart.sh worker-atomic >"$SIM_LOG_DIR/worker-atomic-private-ip-${mode}.log" 2>&1
  for _ in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}' "http://localhost:${WORKER_ATOMIC_PORT}/actuator/health" 2>/dev/null \
      | grep -F 200 >/dev/null && { echo "  [worker-atomic:private-ip-$mode ready]"; return 0; }
    sleep 3
  done
  echo "  [worker-atomic:private-ip-$mode NOT ready] 见 $SIM_LOG_DIR/worker-atomic-private-ip-${mode}.log" >&2
  return 1
}

# ---------------------------------------------------------
# harness 已完成场景初始配置切换，并在阶段后恢复；阶段只拥有场景内部的故障注入重启。
validate_sim_stage_selection() {
  if [[ "$#" == 0 ]]; then
    echo "sim-selected 需要阶段号，例如 23 25" >&2
    return 2
  fi
  local requested seen=" " candidate found
  for requested in "$@"; do
    if [[ ! "$requested" =~ ^(0[4-9]|[12][0-9]|30)$ || "$seen" == *" $requested "* ]]; then
      echo "无效或重复阶段号: $requested" >&2
      return 2
    fi
    found=0
    for candidate in "$ROOT/scripts/sim/$requested"-*.sh; do
      [[ -f "$candidate" ]] && found=1
    done
    if [[ "$found" != 1 ]]; then
      echo "阶段脚本不存在: $requested" >&2
      return 2
    fi
    seen+="$requested "
  done
}

run_sim_stage() {
  local stage="$1"
  case "$(basename "$stage")" in
    23-*) RESTART_IMPORT_WITH_SKIP=0 RESTORE_IMPORT_AFTER_SKIP=0 bash "$stage" ;;
    25-*) RESTART_IMPORT_WITH_CHECKPOINT=0 RESTORE_IMPORT_AFTER_CHECKPOINT=0 bash "$stage" ;;
    21-*)
      restart_atomic_http_private_ip_guard enabled || return 1
      local stage_rc=0 restore_rc=0
      bash "$stage" || stage_rc=$?
      restart_atomic_http_private_ip_guard default || restore_rc=1
      [[ "$stage_rc" == 0 && "$restore_rc" == 0 ]]
      ;;
    *) bash "$stage" ;;
  esac
}

sim() {
  local requested_stages="$*"
  if [[ -n "$requested_stages" ]]; then
    echo "== sim-selected:定向阶段 ${requested_stages}（不作为完整 SIM 结果） =="
  else
    echo "== sim:全量 04→30(零跳过) =="
  fi
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
    source scripts/sim/env-common.sh
    sim_validate_infrastructure_targets ) || return 1
  clean_stale_env
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    local active_import_mode=default active_process_batch_claim=default
    restore_sim_worker_profiles() {
      local original_rc=$? restore_failed=0 import_restore_failed=0 cleanup_failed=0
      trap - EXIT
      set +e
      if [[ "$active_import_mode" != default ]]; then
        restart_import default || { restore_failed=1; import_restore_failed=1; }
      fi
      if [[ "$active_process_batch_claim" != default ]]; then
        restart_process_batch_claim default || restore_failed=1
      fi
      if [[ "$original_rc" == "0" ]]; then
        if [[ "$active_import_mode" == default && "$import_restore_failed" == "0" ]]; then
          clean_sim_minio_inputs post-run || cleanup_failed=1
        else
          echo "  [sim-cleanup] Import Worker 未确认恢复默认配置，保留 SIM 入站对象" >&2
          cleanup_failed=1
        fi
      fi
      if [[ "$original_rc" == 0 && ( "$restore_failed" != 0 || "$cleanup_failed" != 0 ) ]]; then
        echo "  [sim-cleanup] worker 配置恢复或 MinIO SIM 输入清理失败" >&2
        exit 1
      fi
      exit "$original_rc"
    }
    trap restore_sim_worker_profiles EXIT
    ensure_core_runtime
    restart_import default   # 基线 worker:checkpoint=false(17 REPLACE 需要) + no skip
    restart_process_batch_claim default
    ensure_worker_registrations || exit 1
    local sum="$SIM_LOG_DIR/sim-summary.txt"; : > "$sum"
    if [[ -n "$requested_stages" ]]; then
      echo "SCOPE selected: $requested_stages (not a full SIM run)" | tee -a "$sum"
    fi
    local sim_failed=0
    local sim_stage_files
    sim_stage_files="$(find scripts/sim -maxdepth 1 -type f -name '[0-3][0-9]-*.sh' | sort)"
    local stage_manifest="$SIM_LOG_DIR/sim-stage-manifest.txt"
    printf '%s\n' "$sim_stage_files" > "$stage_manifest"
    local actual_stage_ids expected_stage_ids stage_number
    actual_stage_ids="$(printf '%s\n' "$sim_stage_files" | sed -nE 's|.*/([0-9][0-9])-.*|\1|p' | sort | tr '\n' ' ' | sed 's/ $//')"
    expected_stage_ids="00 01 02 03"
    for stage_number in $(seq -w 4 30); do
      expected_stage_ids+=" $stage_number"
      [[ "$stage_number" != "06" ]] || expected_stage_ids+=" 06"
    done
    if [[ "$actual_stage_ids" != "$expected_stage_ids" ]]; then
      echo "ERROR: SIM stage manifest mismatch; expected '$expected_stage_ids', found '$actual_stage_ids'" >&2
      exit 1
    fi
    # 清单使用独立文件描述符，避免 docker exec -i 等辅助命令读取标准输入后，
    # 导致 Worker 重启后的阶段循环被静默截断。
    while IFS= read -r s <&3; do
      local n; n=$(basename "$s")
      # 00-03 是前置(reset/seed/租户导入),harness 单独跑,遍历跳过。
      case "$n" in 00-*|01-*|02-*|03-*) continue;; esac
      if [[ -n "$requested_stages" && " $requested_stages " != *" ${n%%-*} "* ]]; then
        continue
      fi
      # per-stage batchNo 隔离:全局 BATCH_NO 会让各 stage 共享 batchNo,致断言/清理跨 stage
      # 撞数据(12 断言按 source_ref=batchNo 查到他人 file_record、24 DELETE 撞他人
      # trigger_request 的 job_instance FK)。08-25 各自 source env-common,unset 后会生成
      # 自己唯一 batchNo 隔离;04-07 是基线 load/verify 链(05/06-verify 不 source env-common,
      # 依赖 harness 注入的全局 batchNo),保留全局不能 unset(否则 os.environ['BATCH_NO'] KeyError)。
      case "$n" in
        0[4-7]-*) : ;;
        *) unset BATCH_NO RUN_ID REPORT_DIR ;;
      esac
      case "$n" in
        23-*)
          active_import_mode=skip
          restart_import skip
          ;;
        25-*)
          active_import_mode=checkpoint
          restart_import checkpoint
          ;;
        26-*)
          active_import_mode=bundle
          restart_import bundle
          ;;
        27-*)
          active_process_batch_claim=enabled
          restart_process_batch_claim enabled
          ;;
      esac
      ensure_core_runtime || exit 1
      ensure_worker_registrations || exit 1
      echo ">>> $n $(date +%T)" | tee -a "$sum"
      local stage_log="$SIM_LOG_DIR/$n.log"
      # 阶段脚本不能继承 while-read 的 stdin；否则内部命令读取 stdin 时会吞掉后续阶段文件名。
      if run_sim_stage "$s" </dev/null >"$stage_log" 2>&1; then
        if [[ "$n" == 25-* || "$n" == 26-* ]]; then
          if clean_sim_minio_inputs "post-stage-${n%%-*}"; then
            echo "PASS $n" | tee -a "$sum"
          else
            sim_failed=1
            echo "FAIL $n (SIM MinIO input cleanup failed)" | tee -a "$sum"
          fi
        else
          echo "PASS $n" | tee -a "$sum"
        fi
      else
        local rc=$?
        sim_failed=1
        echo "FAIL $n (exit $rc)" | tee -a "$sum"
        tail -6 "$stage_log" | sed 's/^/   /' | tee -a "$sum"
      fi
      case "$n" in
        23-*|25-*|26-*)
          restart_import default
          active_import_mode=default
          ;;
        27-*)
          restart_process_batch_claim default
          active_process_batch_claim=default
          ;;
      esac
      case "$n" in *load*|*stage*) sleep 20;; esac
    done 3< "$stage_manifest"
    echo "== sim 完成 ==" | tee -a "$sum"
    exit "$sim_failed" )
}

# ---------------------------------------------------------
# routing-sim:开真实 worker 多片路由,端到端验「租户落对片」(新开关 sim 验证)
#   1) shard-1 up  2) 写 routing env 覆盖(TABLE,2 片)+ 重启 3 biz worker
#   3) 把 tc 钉到 shard-1(placement 表)  4) 跑 04-seed+05-load 导入
#   5) 验:tc 的 biz 行落 shard-1、shard-0 无  6) 还原 env + 重启
# ---------------------------------------------------------
ROUTING_MARK_BEGIN="# >>> sim-harness routing overlay"
ROUTING_MARK_END="# <<< sim-harness routing overlay"

# 仅清 overlay + 还原单片 worker;run 中途复用(此时 shard-1 还在用,不能删)。
routing_overlay_clear() {
  # 删除 overlay 块,还原单片,重启 3 worker
  local env_file="$COMPOSE_ENV_FILE"
  if grep -qF "$ROUTING_MARK_BEGIN" "$env_file" 2>/dev/null; then
    # 可移植删块:避开 sed -i 的 BSD(需 '' 后缀)/ GNU(不带后缀)差异 —— 同目录 tmp + mv,Linux/macOS 通用。
    if ! sed "/$ROUTING_MARK_BEGIN/,/$ROUTING_MARK_END/d" "$env_file" > "$env_file.tmp" \
        || ! mv "$env_file.tmp" "$env_file"; then
      c_red "routing overlay 清理失败；保留 SIM 并发配置状态，停止后续清理"
      return 1
    fi
    c_ylw "  routing overlay 已从所选环境文件移除,重启 worker 还原单片"
    unset BATCH_ENV_LOADED
    if ! bash scripts/local/restart.sh worker-import worker-export worker-process >"$SIM_LOG_DIR/routing-restore.log" 2>&1; then
      c_red "routing worker 恢复失败(见 $SIM_LOG_DIR/routing-restore.log)"
      return 1
    fi
  fi
}

# EXIT trap 收尾:overlay 还原 + 删 routing 直起的 biz-shard-1(docker run 起的,不归 compose 管,
# 不删就会残留到下一轮)。只在退出时调,run 中途用 routing_overlay_clear。
routing_sim_teardown() {
  local run_status=$? cleanup_status=0
  trap - EXIT
  routing_overlay_clear || cleanup_status=$?
  cleanup_sim_owned_biz_shard || cleanup_status=$?
  cleanup_sim_shard_secrets || cleanup_status=$?
  if [[ "$run_status" -ne 0 ]]; then
    exit "$run_status"
  fi
  exit "$cleanup_status"
}

routing_sim() {
  echo "== routing-sim:真实 worker 多片端到端 =="
  clean_stale_env
  trap routing_sim_teardown EXIT

  echo "-- 1) shard-1 + secrets --"
  BATCH_SIM_RESIDUE_CONTAINER=true \
    bash scripts/local/provision-biz-shard.sh shard-1 "${BIZ_SHARD_1_PORT:-15442}" \
      >"$SIM_LOG_DIR/rs-shard1.log" 2>&1 && ok "shard-1" || { fail "shard-1"; return 1; }
  if [[ ! -f "$BIZ_SHARD_SECRETS_DIR/shard-0.env" ]]; then
    local shard0_url
    shard0_url="jdbc:postgresql://$(batch_format_host_port "${PGHOST:-localhost}" "${POSTGRES_PORT:-15432}")/$BUSINESS_DB?currentSchema=biz&reWriteBatchedInserts=true"
    umask 077
    {
      printf 'BIZ_SHARD_URL="%s"\n' "$shard0_url"
      printf 'BIZ_SHARD_USERNAME=%s\n' "$POSTGRES_USER"
      printf 'BIZ_SHARD_PASSWORD=%s\n' "$POSTGRES_PASSWORD"
    } > "$BIZ_SHARD_SECRETS_DIR/shard-0.env"
    chmod 600 "$BIZ_SHARD_SECRETS_DIR/shard-0.env"
  fi
  local s0u s0n s0p s1u s1n s1p
  s0u=$(grep BIZ_SHARD_URL "$BIZ_SHARD_SECRETS_DIR/shard-0.env"|cut -d= -f2-|tr -d '"'); s0n=$(grep BIZ_SHARD_USERNAME "$BIZ_SHARD_SECRETS_DIR/shard-0.env"|cut -d= -f2-); s0p=$(grep BIZ_SHARD_PASSWORD "$BIZ_SHARD_SECRETS_DIR/shard-0.env"|cut -d= -f2-)
  s1u=$(grep BIZ_SHARD_URL "$BIZ_SHARD_SECRETS_DIR/shard-1.env"|cut -d= -f2-|tr -d '"'); s1n=$(grep BIZ_SHARD_USERNAME "$BIZ_SHARD_SECRETS_DIR/shard-1.env"|cut -d= -f2-); s1p=$(grep BIZ_SHARD_PASSWORD "$BIZ_SHARD_SECRETS_DIR/shard-1.env"|cut -d= -f2-)

  echo "-- 2a) placement: tc -> shard-1(重启前先登记,worker 启动即可 resolve)--"
  if sim_platform_sql upsert-business-tenant-placement.sql \
    -v tenant_id=tc -v placement_key=shard-1 -v updated_by=routing-sim >/dev/null; then
    ok "tc→shard-1 已登记"
  else
    fail "tc→shard-1 登记失败"
    return 1
  fi

  echo "-- 2b) 写 routing overlay + 重启 worker --"
  routing_overlay_clear  # 先清旧 overlay(此处不删 shard,shard-1 正在用)
  {
    echo "$ROUTING_MARK_BEGIN"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED=true"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_POOLED_SHARD_COUNT=2"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_PLACEMENT_SOURCE=TABLE"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_KEY=shard-0"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_URL=$s0u"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_USERNAME=$s0n"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_0_PASSWORD=$s0p"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_KEY=shard-1"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_URL=$s1u"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_USERNAME=$s1n"
    echo "BATCH_DATASOURCE_BUSINESS_ROUTING_SHARDS_1_PASSWORD=$s1p"
    echo "$ROUTING_MARK_END"
  } >> "$COMPOSE_ENV_FILE"
  unset BATCH_ENV_LOADED
  bash scripts/local/restart.sh worker-import worker-export worker-process >"$SIM_LOG_DIR/rs-restart.log" 2>&1
  for _ in $(seq 1 30); do
    curl -s -o /dev/null -w '%{http_code}' "http://localhost:${WORKER_IMPORT_PORT}/actuator/health" 2>/dev/null \
      | grep -F 200 >/dev/null && break
    sleep 3
  done
  ok "3 biz worker 已带 routing 重启"

  echo "-- 4) 跑导入(04-seed + 05-load)--"
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    bash scripts/sim/04-seed-source-data.sh >"$SIM_LOG_DIR/rs-seed.log" 2>&1
    bash scripts/sim/05-load.sh >"$SIM_LOG_DIR/rs-load.log" 2>&1 ) && ok "导入触发完成" || warn "导入有非零退出(看日志)"
  sleep 30

  echo "-- 5) 验:tc 业务数据落 shard-1 而非 shard-0 --"
  local tbl="customer_account"  # ta 写 customer;tc 写 risk_score。两边都查,任一有 tc 行即证
  local on1 on0
  for tbl in risk_score customer_account transaction; do
    on1=$(PGPASSWORD="$s1p" docker run --rm -i --network "$(docker inspect "$PG" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')" -e PGPASSWORD="$s1p" postgres:17.11 \
      psql -X -v ON_ERROR_STOP=1 -h "$BIZ_SHARD_1_CONTAINER" -U "$s1n" -d "$BIZ_DB" -tA \
      -v table_name="$tbl" -v tenant_id=tc -f /dev/stdin < "$SIM_SQL_DIR/count-business-tenant-rows.sql" 2>/dev/null || echo 0)
    on0=$(sim_business_sql count-business-tenant-rows.sql -tA \
      -v table_name="$tbl" -v tenant_id=tc 2>/dev/null || echo 0)
    echo "    biz.$tbl  tc@shard-1=$on1  tc@shard-0=$on0"
  done
  c_grn "== routing-sim 完成(看上面 tc 是否落 shard-1;EXIT 自动还原单片)=="
}

case "${1:-}" in
  preflight)   preflight ;;
  reset)       reset ;;
  prereq)      prereq ;;
  verify-data) verify_data ;;
  cleanup-inputs) cleanup_sim_inputs ;;
  sim)         sim ;;
  sim-selected) shift; validate_sim_stage_selection "$@" && sim "$@" ;;
  routing-sim) routing_sim ;;
  # reset 必须先于服务启动，避免旧 scheduler/worker 在清理窗口写入运行态；
  # prereq 的租户导入又依赖 console 已就绪，因此在两者之间用小堆重启并等待核心服务。
  all)         all ;;
  *) echo "用法: $0 {preflight|reset|prereq|verify-data|cleanup-inputs|sim|sim-selected STAGE...|routing-sim|all}"; exit 2 ;;
esac
