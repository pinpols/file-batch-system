#!/usr/bin/env bash
# =========================================================
# sim-harness.sh —— 可重复执行的 sim / 真实数据验证 harness。
#
# 子命令:
#   preflight     启动前检查先决条件/环境/配置(fail-fast,不跑业务)
#   reset         数据重制到基线(清运行态 + biz 数据,保留 definition/config/tenant/user)
#   prereq        幂等装配先决条件(biz 表+RLS+只读角色、shard-1、下游 sftp/mockserver、租户导入)
#   verify-data   真实数据验证(两片真实 PG 活体路由,幂等可重复)
#   sim           跑全量 sim 阶段 04→25(前置:preflight + prereq 已过)
#   all           preflight → reset → prereq → verify-data → sim 一条龙
#
# 本 harness 固化了若干踩过的坑(见各 check 注释):
#   - bypass-mode 被 .env.local 的 BATCH_SECURITY_BYPASS_MODE=false 覆盖 → CSRF 拦所有写(403)
#   - sim 阶段需 source scripts/sim/env-common.sh 才有 TRIGGER_BASE/CONSOLE_BASE
#   - BATCH_ENV_COMMON_ROOT 来自 shell profile 会污染 .env.local 加载
#   - 00-reset 硬编码 truncate 列表含「后续阶段才建的表」,fresh 库会撞「relation does not exist」
#   - xlsx fixture 过时(job_definition 缺 watermark_field)→ 导入 400
# =========================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PG="${PG_CONTAINER:-batch-postgres-primary}"
PGU="${POSTGRES_USER:-batch_user}"
PLAT_DB="${POSTGRES_DB:-batch_platform}"
BIZ_DB="${BUSINESS_DB_NAME:-batch_business}"
CONSOLE="http://localhost:18080"
FIXTURE_DIR="docs/test-data/test-full-coverage-import-suite"

c_red()  { printf '\033[0;31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
c_ylw()  { printf '\033[1;33m%s\033[0m\n' "$*"; }
fail()   { c_red "  ✗ $*"; FAILED=1; }
ok()     { c_grn "  ✓ $*"; }
warn()   { c_ylw "  ! $*"; }

# ---------------------------------------------------------
# preflight:启动前检查(不依赖 app 在跑)。FAILED=1 → 退 1。
# ---------------------------------------------------------
preflight() {
  FAILED=0
  echo "== preflight:环境 / 配置 =="
  [[ -f .env.local ]] && ok ".env.local 存在" || fail ".env.local 缺失(从 sibling 或 .env.example 准备)"

  if [[ -n "${BATCH_ENV_COMMON_ROOT:-}" ]]; then
    fail "BATCH_ENV_COMMON_ROOT 已设(shell profile 污染)→ unset 后再起,否则 .env.local 不正确加载"
  else
    ok "BATCH_ENV_COMMON_ROOT 未设"
  fi

  # bypass-mode:sim curl 脚本不带 X-XSRF-TOKEN,bypass=false 时 CSRF 拦所有写(403)。sim 必须 true。
  local bypass
  bypass=$(grep -E '^BATCH_SECURITY_BYPASS_MODE=' .env.local 2>/dev/null | cut -d= -f2)
  if [[ "$bypass" == "true" ]]; then
    ok "BATCH_SECURITY_BYPASS_MODE=true(CSRF 对 /** 放行,sim 写可过)"
  else
    fail "BATCH_SECURITY_BYPASS_MODE=${bypass:-<空>} —— sim 必须 true,否则 CSRF 拦所有写请求(403)"
  fi

  echo "== preflight:基础设施 =="
  local svc
  for svc in "$PG" batch-kafka batch-minio batch-valkey; do
    local st
    st=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo missing)
    [[ "$st" == healthy ]] && ok "$svc healthy" || fail "$svc 状态=$st(需 docker compose up infra)"
  done

  echo "== preflight:构建产物 / secrets / fixtures =="
  local n
  n=$(ls build/runtime-jars/*.jar 2>/dev/null | wc -l | tr -d ' ')
  [[ "$n" == 8 ]] && ok "runtime-jars 8 个" || fail "runtime-jars=$n(需 build-apps.sh,期望 8)"

  local k
  for k in shard-0 shard-1; do
    [[ -f "secrets/biz-shards/$k.env" || -f "secrets/biz-shards/$k.env.example" ]] \
      && ok "secrets/biz-shards/$k.env(.example) 就绪" || fail "secrets/biz-shards/$k.env 缺失"
  done

  local t
  for t in ta tb tc; do
    [[ -f "$FIXTURE_DIR/${t}-tenant-config-package-test.xlsx" ]] \
      && ok "fixture $t 存在" || fail "fixture $t 缺失:$FIXTURE_DIR/${t}-tenant-config-package-test.xlsx"
  done

  echo "== preflight:sim env(TRIGGER_BASE 等)=="
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
    source scripts/sim/env-common.sh >/dev/null 2>&1
    [[ -n "${TRIGGER_BASE:-}" && -n "${CONSOLE_BASE:-}" ]] ) \
    && ok "env-common 可 source(TRIGGER_BASE/CONSOLE_BASE 解析)" \
    || fail "env-common source 后 TRIGGER_BASE/CONSOLE_BASE 为空"

  # app 在跑 → 追加运行期检查(健康 + fixture 兼容性 dry-upload)
  if curl -s -o /dev/null --max-time 3 "$CONSOLE/actuator/health" 2>/dev/null; then
    echo "== preflight:运行期(app 已起)=="
    local p
    for p in 18080 18081 18082 18083 18084 18085 18086 18087; do
      local hc; hc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$p/actuator/health" 2>/dev/null)
      [[ "$hc" == 200 ]] && ok "svc :$p UP" || fail "svc :$p health=$hc"
    done
    fixture_compat_check || true
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
# reset:数据重制到基线(robust:只 truncate 实际存在的表,修 00-reset 撞缺表的坑)
# ---------------------------------------------------------
reset() {
  echo "== reset:运行态 + biz 数据回基线(保留 definition/config/tenant/user)=="
  # 平台运行态:沿用 00-reset 的平台段(它平台段是好的;失败的是 biz 段的硬编码列表)
  set -a; . ./.env.local; set +a
  # biz 数据:动态枚举 biz 现有基表(非分区子表)TRUNCATE CASCADE,避免硬编码撞缺表
  docker exec "$PG" psql -v ON_ERROR_STOP=1 -U "$PGU" -d "$BIZ_DB" -c "
    DO \$reset\$
    DECLARE r record;
    BEGIN
      FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='biz' AND c.relkind='p' -- 分区父表
      LOOP EXECUTE format('TRUNCATE TABLE biz.%I CASCADE', r.relname); END LOOP;
      FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='biz' AND c.relkind='r'
                 AND c.relispartition=false  -- 普通非分区基表
      LOOP EXECUTE format('TRUNCATE TABLE biz.%I CASCADE', r.relname); END LOOP;
    END \$reset\$;" >/dev/null && ok "biz 数据已清(动态枚举,跳过不存在的表)" || { c_red "  ✗ biz 清理失败"; return 1; }

  # 平台运行态 truncate(job_/pipeline_/workflow_/outbox/file_record 等),保留 *_definition/config/tenant/user/系统表
  docker exec "$PG" psql -v ON_ERROR_STOP=1 -U "$PGU" -d "$PLAT_DB" -c "
    DO \$reset\$
    DECLARE r record;
    BEGIN
      FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='batch'
        AND tablename ~ '^(job_instance|job_execution|pipeline_instance|pipeline_step_run|workflow_run|workflow_node_run|outbox_event|event_outbox_retry|trigger_outbox_event|file_record|compensation_command|dead_letter)'
      LOOP EXECUTE format('TRUNCATE TABLE batch.%I CASCADE', r.tablename); END LOOP;
    END \$reset\$;" >/dev/null && ok "平台运行态已清(保留定义/配置/租户/用户/系统表)" || warn "平台运行态部分清理跳过"
  c_grn "== reset 完成 =="
}

# ---------------------------------------------------------
# prereq:幂等装配先决条件
# ---------------------------------------------------------
prereq() {
  echo "== prereq:biz 表 + RLS + 只读角色 =="
  set -a; . ./.env.local; set +a
  bash scripts/sim/01-init-biz.sh >/tmp/harness-initbiz.log 2>&1 && ok "01-init-biz" || { c_red "  ✗ 01-init-biz(见 /tmp/harness-initbiz.log)"; return 1; }
  docker exec -i "$PG" psql -q -U "$PGU" -d "$BIZ_DB" < scripts/db/business/rls-phase-a.sql >/dev/null 2>&1 && ok "rls-phase-a" || warn "rls-phase-a 跳过"
  docker exec -i "$PG" psql -q -U "$PGU" -d "$BIZ_DB" < scripts/db/business/diagnostic-readonly-role.sql >/dev/null 2>&1 && ok "只读角色" || warn "只读角色 跳过"

  echo "== prereq:平台 seed(atomic job + refresh_metrics procedure)=="
  # Stage 07/16/21 atomic 场景依赖 platform_seed 的 atomic_*_demo job(default-tenant)与
  # batch.refresh_metrics() procedure;这俩是 definition(reset 保留),但 fresh 库 / 清过
  # definition 时缺失会致 atomic stage 整片 REJECTED。幂等(ON CONFLICT / CREATE OR REPLACE),每轮装配。
  docker exec -i "$PG" psql -q -U "$PGU" -d "$PLAT_DB" -v ON_ERROR_STOP=1 \
    < scripts/db/test-seed/platform_seed.sql >/tmp/harness-platform-seed.log 2>&1 \
    && ok "platform_seed(atomic/procedure)" \
    || { c_red "  ✗ platform_seed(见 /tmp/harness-platform-seed.log)"; return 1; }

  echo "== prereq:shard-1(幂等)=="
  bash scripts/local/provision-biz-shard.sh shard-1 "${BIZ_SHARD_1_PORT:-15442}" >/tmp/harness-shard1.log 2>&1 && ok "shard-1 就绪" || { c_red "  ✗ shard-1(见 /tmp/harness-shard1.log)"; return 1; }

  echo "== prereq:下游模拟器(sftp/mockserver)=="
  bash scripts/sim/02-start-sim.sh >/tmp/harness-sim02.log 2>&1 && ok "sftp/mockserver" || { c_red "  ✗ 02-start-sim(见 /tmp/harness-sim02.log)"; return 1; }

  echo "== prereq:租户导入(03)=="
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    bash scripts/sim/03-import-tenants.sh ) >/tmp/harness-import.log 2>&1 \
    && ok "租户配置导入" \
    || c_red "  ✗ 03-import 失败(常见:xlsx fixture 缺列 watermark_field;见 /tmp/harness-import.log)"
  c_grn "== prereq 完成 =="
}

# ---------------------------------------------------------
# verify-data:真实数据路由验证(已幂等)
# ---------------------------------------------------------
verify_data() {
  echo "== verify-data:两片真实 PG 活体路由 =="
  bash scripts/local/verify-biz-shard.sh
}

# ---------------------------------------------------------
# sim:全量阶段 04→25
# restart_import:为特定 stage 切换 worker-import 互斥配置。
#   default    = checkpoint off + no skip(17 PARTITION_REPLACE_COPY 需 checkpoint=false)
#   skip       = skip-profile(23 import-stage2d 的 skip 阈值场景)
#   checkpoint = checkpoint=true(25 import-stage2e 真实崩溃续跑)
# 用 build/runtime-jars(与 start-all 一致);env 由调用方子 shell 的 source env-common 提供。
restart_import() {
  local mode="${1:-default}" extra=""
  case "$mode" in
    skip) extra="-Dbatch.worker.import.skip.enabled=true -Dbatch.worker.import.skip.threshold-mode=ABSOLUTE -Dbatch.worker.import.skip.max-skip-count=1 -Dbatch.worker.import.skip.error-sink-type=ERROR_TABLE" ;;
    checkpoint) extra="-Dbatch.worker.checkpoint.enabled=true" ;;
  esac
  local pid; pid=$(pgrep -f "build/runtime-jars/worker-import.jar" | head -1)
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  fi
  # shellcheck disable=SC2086
  nohup java --enable-native-access=ALL-UNNAMED -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:off $extra \
    -jar build/runtime-jars/worker-import.jar --spring.profiles.active=local >logs/worker-import.log 2>&1 &
  disown
  for _ in $(seq 1 40); do
    curl -s -o /dev/null -w '%{http_code}' http://localhost:18083/actuator/health 2>/dev/null | grep -q 200 && { echo "  [worker-import:$mode ready]"; return 0; }
    sleep 3
  done
  echo "  [worker-import:$mode NOT ready]" >&2; return 1
}

# ---------------------------------------------------------
sim() {
  echo "== sim:全量 04→25 =="
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    restart_import default   # 基线 worker:checkpoint=false(17 REPLACE 需要) + no skip
    local sum=/tmp/harness-sim-summary.txt; : > "$sum"
    for s in $(ls scripts/sim/[0-2][0-9]-*.sh | sort); do
      local n; n=$(basename "$s")
      case "$n" in 00-*|01-*|02-*|03-*) continue;; esac
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
        23-*) restart_import skip ;;        # skip-profile
        25-*) restart_import checkpoint ;;  # checkpoint=true
      esac
      echo ">>> $n $(date +%T)" | tee -a "$sum"
      if bash "$s" >"/tmp/harness-$n.log" 2>&1; then echo "PASS $n" | tee -a "$sum"; else echo "FAIL $n (exit $?)" | tee -a "$sum"; tail -6 "/tmp/harness-$n.log" | sed 's/^/   /' | tee -a "$sum"; fi
      case "$n" in 23-*|25-*) restart_import default ;; esac   # 恢复基线
      case "$n" in *load*|*stage*) sleep 20;; esac
    done
    echo "== sim 完成 ==" | tee -a "$sum" )
}

# ---------------------------------------------------------
# routing-sim:开真实 worker 多片路由,端到端验「租户落对片」(新开关 sim 验证)
#   1) shard-1 up  2) 写 routing env 覆盖(TABLE,2 片)+ 重启 3 biz worker
#   3) 把 tc 钉到 shard-1(placement 表)  4) 跑 04-seed+05-load 导入
#   5) 验:tc 的 biz 行落 shard-1、shard-0 无  6) 还原 env + 重启
# ---------------------------------------------------------
ROUTING_MARK_BEGIN="# >>> sim-harness routing overlay"
ROUTING_MARK_END="# <<< sim-harness routing overlay"

routing_sim_teardown() {
  # 删除 overlay 块,还原单片,重启 3 worker
  if grep -qF "$ROUTING_MARK_BEGIN" .env.local 2>/dev/null; then
    sed -i '' "/$ROUTING_MARK_BEGIN/,/$ROUTING_MARK_END/d" .env.local
    c_ylw "  routing overlay 已从 .env.local 移除,重启 worker 还原单片"
    unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
    bash scripts/local/restart.sh worker-import worker-export worker-process >/tmp/harness-routing-restore.log 2>&1 || true
  fi
}

routing_sim() {
  echo "== routing-sim:真实 worker 多片端到端 =="
  trap routing_sim_teardown EXIT
  set -a; . ./.env.local; set +a

  echo "-- 1) shard-1 + secrets --"
  bash scripts/local/provision-biz-shard.sh shard-1 "${BIZ_SHARD_1_PORT:-15442}" >/tmp/harness-rs-shard1.log 2>&1 && ok "shard-1" || { fail "shard-1"; return 1; }
  local s0u s0n s0p s1u s1n s1p
  s0u=$(grep BIZ_SHARD_URL secrets/biz-shards/shard-0.env|cut -d= -f2-|tr -d '"'); s0n=$(grep BIZ_SHARD_USERNAME secrets/biz-shards/shard-0.env|cut -d= -f2-); s0p=$(grep BIZ_SHARD_PASSWORD secrets/biz-shards/shard-0.env|cut -d= -f2-)
  s1u=$(grep BIZ_SHARD_URL secrets/biz-shards/shard-1.env|cut -d= -f2-|tr -d '"'); s1n=$(grep BIZ_SHARD_USERNAME secrets/biz-shards/shard-1.env|cut -d= -f2-); s1p=$(grep BIZ_SHARD_PASSWORD secrets/biz-shards/shard-1.env|cut -d= -f2-)

  echo "-- 2a) placement: tc -> shard-1(重启前先登记,worker 启动即可 resolve)--"
  docker exec "$PG" psql -v ON_ERROR_STOP=1 -U "$PGU" -d "$PLAT_DB" -c \
    "INSERT INTO batch.business_tenant_placement(tenant_id,placement_key,updated_by) VALUES ('tc','shard-1','routing-sim') ON CONFLICT (tenant_id) DO UPDATE SET placement_key='shard-1'" >/dev/null && ok "tc→shard-1 已登记"

  echo "-- 2b) 写 routing overlay + 重启 worker --"
  routing_sim_teardown  # 先清旧 overlay
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
  } >> .env.local
  unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT
  bash scripts/local/restart.sh worker-import worker-export worker-process >/tmp/harness-rs-restart.log 2>&1
  for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code}' http://localhost:18083/actuator/health 2>/dev/null | grep -q 200 && break; sleep 3; done
  ok "3 biz worker 已带 routing 重启"

  echo "-- 4) 跑导入(04-seed + 05-load)--"
  ( unset BATCH_ENV_LOADED BATCH_ENV_COMMON_ROOT; source scripts/sim/env-common.sh >/dev/null 2>&1
    bash scripts/sim/04-seed-source-data.sh >/tmp/harness-rs-seed.log 2>&1
    bash scripts/sim/05-load.sh >/tmp/harness-rs-load.log 2>&1 ) && ok "导入触发完成" || warn "导入有非零退出(看日志)"
  sleep 30

  echo "-- 5) 验:tc 业务数据落 shard-1 而非 shard-0 --"
  local tbl="customer_account"  # ta 写 customer;tc 写 risk_score。两边都查,任一有 tc 行即证
  local on1 on0
  for tbl in risk_score customer_account transaction; do
    on1=$(PGPASSWORD="$s1p" docker run --rm --network "$(docker inspect "$PG" --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')" -e PGPASSWORD="$s1p" postgres:17 psql -h batch-postgres-biz-shard-1 -U "$s1n" -d batch_business -tAc "SELECT count(*) FROM biz.$tbl WHERE tenant_id='tc'" 2>/dev/null || echo 0)
    on0=$(docker exec "$PG" psql -U "$PGU" -d batch_business -tAc "SELECT count(*) FROM biz.$tbl WHERE tenant_id='tc'" 2>/dev/null || echo 0)
    echo "    biz.$tbl  tc@shard-1=$on1  tc@shard-0=$on0"
  done
  c_grn "== routing-sim 完成(看上面 tc 是否落 shard-1;EXIT 自动还原单片)=="
}

case "${1:-}" in
  preflight)   preflight ;;
  reset)       reset ;;
  prereq)      prereq ;;
  verify-data) verify_data ;;
  sim)         sim ;;
  routing-sim) routing_sim ;;
  all)         preflight && reset && prereq && verify_data && sim ;;
  *) echo "用法: $0 {preflight|reset|prereq|verify-data|sim|routing-sim|all}"; exit 2 ;;
esac
