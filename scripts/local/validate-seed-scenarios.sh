#!/usr/bin/env bash
# =========================================================
# validate-seed-scenarios.sh
#
# 标准化种子数据 + ADR-010 全链路 + 多租隔离 + 异常路径验证脚本。
# 支持 docker 容器栈或本地 jvm 进程,自动探测端口。
#
# 退出码:0 = 全部通过;1 = 至少 1 项失败。
# 输出:每项 🟢/🔴/🟡 + 一句话原因 + 末尾汇总表。
#
# 重跑要点:幂等 — 探针行用唯一 trace_id 隔离,run 完自动清理。
#
# 覆盖范围(31 项, 详见汇总表):
#   - 探活: trigger / orch / postgres
#   - Schema: V82-V85 + 约束 + tenant_id 列
#   - 种子基线: job_definition / workflow_node 行数
#   - V84 多租隔离: 跨租户同 (wf_def, node_code) 共存
#   - 同步 API + ADR-010 异步链路: trigger_outbox → relay → Kafka → orch consumer
#   - 异常路径 ×5: 缺字段 / 缺幂等键 / 缺 secret / 重发去重 / 跨租户拒绝
#   - Worker 链路覆盖 ×7: IMPORT/EXPORT/DISPATCH + 4 种 workflow_type
#     (DAG/PIPELINE/GATEWAY/MIXED), 验证 instance 创建,不强制 worker SUCCESS
#   - 多租并发隔离: ta/tb 同时 fire,验证 tenant_id 不串
#   - ADVANCED=1: orch /internal/{outbox,compensations} + console 鉴权 (5 项)
#   - 探针清理: cascade 删 7 张表
#
# 用法:
#   ./scripts/local/validate-seed-scenarios.sh                # 默认 docker 端口 18081 等
#   TRIGGER_PORT=18081 ORCH_PORT=18082 \
#     INTERNAL_SECRET=internal-secret \
#     ./scripts/local/validate-seed-scenarios.sh
#
# 前置: 需先加载 multi-tenant-seed 才覆盖 ta/tb/tc 场景:
#   docker cp batch-e2e-tests/src/test/resources/db/testdata/multi-tenant-seed.sql \
#     batch-postgres:/tmp/ && docker exec batch-postgres \
#     psql -U batch_user -d batch_platform -f /tmp/multi-tenant-seed.sql
#
# 环境变量:
#   TRIGGER_PORT / ORCH_PORT / CONSOLE_PORT  服务端口(默认 18081/18082/18080)
#   INTERNAL_SECRET                          trigger /api/* 共享密钥(默认 internal-secret)
#   PG_CONTAINER                             PG 容器名(默认 batch-postgres)
#   PG_USER / PG_DB                          (默认 batch_user / batch_platform)
#   AWAIT_TIMEOUT                            异步链路等待秒数(默认 30)
#   LOAD_SEED                                1=运行前先 reload 种子(默认 0)
#   ADVANCED                                 1=跑 §9 advanced 段(默认 0)
#   STRICT                                   1=§7 改用 default-tenant 严格 SUCCESS 验证(默认 0=ta/tb/tc 仅链路覆盖)
#                                            STRICT=1 会先探活四类 worker(import/export/dispatch/process)，端口默认
#                                            18083–18086（与 start-all.sh 一致）；worker 默认租户均为 default-tenant，勿改成 ta/tb/tc。
#   BATCH_WORKER_IMPORT_PORT / EXPORT_PORT / DISPATCH_PORT / PROCESS_PORT
#                                            STRICT 前置探活覆盖（默认 18083 / 18084 / 18085 / 18086）
#   PRE_CLEANUP                              0=跳过跑前 sweep(默认 1=清所有 seedval-* 历史)
# =========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

TRIGGER_PORT="${TRIGGER_PORT:-18081}"
ORCH_PORT="${ORCH_PORT:-18082}"
CONSOLE_PORT="${CONSOLE_PORT:-18080}"
INTERNAL_SECRET="${INTERNAL_SECRET:-internal-secret}"
PG_CONTAINER="${PG_CONTAINER:-batch-postgres}"
PG_USER="${PG_USER:-batch_user}"
PG_DB="${PG_DB:-batch_platform}"
AWAIT_TIMEOUT="${AWAIT_TIMEOUT:-30}"
LOAD_SEED="${LOAD_SEED:-0}"
ADVANCED="${ADVANCED:-0}"
PRE_CLEANUP="${PRE_CLEANUP:-1}"   # 1=跑前先全清 seedval-* 历史残留(默认)
STRICT="${STRICT:-0}"             # 1=§7 用 default-tenant 严格 SUCCESS 验证

# 探针标识 — 默认带时间戳便于日志区分 run, 但清理走 'seedval-%' 全 sweep 不漏历史
PROBE_TAG="${PROBE_TAG:-seedval-$(date +%s)}"
# 全 sweep 模式: 跑前 + 跑后 + EXIT trap 都按这个 LIKE 模式清, 保证零残留
SWEEP_PATTERN="seedval-%"

# 颜色
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'
PASS_TAG="${GREEN}🟢 PASS${NC}"; FAIL_TAG="${RED}🔴 FAIL${NC}"; SKIP_TAG="${YELLOW}🟡 SKIP${NC}"

# 汇总
declare -a RESULTS=()
PASS=0; FAIL=0; SKIP=0

result() {
  local tag="$1"; local name="$2"; local detail="$3"
  local line=""
  case "$tag" in
    pass) line="$PASS_TAG $name — $detail"; PASS=$((PASS+1)) ;;
    fail) line="$FAIL_TAG $name — $detail"; FAIL=$((FAIL+1)) ;;
    skip) line="$SKIP_TAG $name — $detail"; SKIP=$((SKIP+1)) ;;
  esac
  RESULTS+=("$line")
  echo -e "  $line"
}

section() { echo -e "\n${BLUE}== $1 ==${NC}"; }

psql_q() {
  # -tA 去掉对齐和表头;但 INSERT...RETURNING 会多输出 "INSERT 0 N" 提示行,
  # 调用方需要时自己 head -1 或过滤
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null
}

# 写入用 — 仅返回 RETURNING 的首行(过滤 "INSERT 0 N" / "DELETE N" 等命令统计)
psql_w_first() {
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null \
    | grep -vE "^(INSERT|UPDATE|DELETE|COMMIT|ROLLBACK)" | head -1
}

# do_cleanup [probe_pattern]
# 默认 'seedval-%' 全 sweep — 把所有历史 PROBE_TAG 痕迹都清掉
# 使用 cascade 顺序: 子表 → 父表(避免 FK 违反, 即使没有 FK 也按依赖序删)
# 8 张表: 11 个 DELETE
do_cleanup() {
  local pattern="${1:-$SWEEP_PATTERN}"
  # 关键 FK: job_instance.trigger_request_id 反向引用 trigger_request
  # 所以必须先删所有由本 PROBE 关联的 job_instance(双向反查),才能删 trigger_request
  # 反查 job_instance.id 集合 = 通过 trigger_request_id 联到的(不依赖 related_job_instance_id 是否回填)
  local probe_instances
  # 双向反查 job_instance: ① 通过 PROBE trigger_request 联到的(API fire); ② job_code LIKE PROBE
  # (CRON 自动 fire 的 instance trigger_request.request_id 自动生成不带 PROBE_TAG, 必须按 job_code 兜底)
  probe_instances=$(psql_q "SELECT string_agg(DISTINCT id::text, ',') FROM batch.job_instance WHERE trigger_request_id IN (SELECT id FROM batch.trigger_request WHERE request_id LIKE '$pattern' OR job_code LIKE '$pattern') OR job_code LIKE '$pattern'")
  if [[ -n "$probe_instances" ]]; then
    # 衍生 cascade(子表先删, 父表后删, 走 FK 安全顺序):
    psql_q "DELETE FROM batch.pipeline_step_run WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN ($probe_instances))" >/dev/null
    psql_q "DELETE FROM batch.pipeline_instance WHERE related_job_instance_id IN ($probe_instances)" >/dev/null
    psql_q "DELETE FROM batch.file_dispatch_record WHERE pipeline_instance_id IN (SELECT id FROM batch.pipeline_instance WHERE related_job_instance_id IN ($probe_instances)) OR file_id IN (SELECT id FROM batch.file_record WHERE source_ref IN (SELECT instance_no FROM batch.job_instance WHERE id IN ($probe_instances)))" >/dev/null
    psql_q "DELETE FROM batch.file_record WHERE source_ref IN (SELECT instance_no FROM batch.job_instance WHERE id IN ($probe_instances))" >/dev/null
    psql_q "DELETE FROM batch.workflow_node_run WHERE workflow_run_id IN (SELECT id FROM batch.workflow_run WHERE related_job_instance_id IN ($probe_instances))" >/dev/null
    psql_q "DELETE FROM batch.workflow_run WHERE related_job_instance_id IN ($probe_instances)" >/dev/null
    psql_q "DELETE FROM batch.job_step_instance WHERE job_instance_id IN ($probe_instances)" >/dev/null
    psql_q "DELETE FROM batch.job_task WHERE job_instance_id IN ($probe_instances)" >/dev/null
    psql_q "DELETE FROM batch.job_partition WHERE job_instance_id IN ($probe_instances)" >/dev/null
    psql_q "DELETE FROM batch.job_execution_log WHERE job_instance_id IN ($probe_instances)" >/dev/null
    # event_delivery_log FK 引用 outbox_event,必须先删;否则下面 outbox 删撞 FK
    psql_q "DELETE FROM batch.event_delivery_log WHERE outbox_event_id IN (SELECT id FROM batch.outbox_event WHERE aggregate_id::text IN (SELECT unnest(string_to_array('$probe_instances', ','))))" >/dev/null
    psql_q "DELETE FROM batch.outbox_event WHERE aggregate_id::text IN (SELECT unnest(string_to_array('$probe_instances', ',')))" >/dev/null
    psql_q "DELETE FROM batch.job_instance WHERE id IN ($probe_instances)" >/dev/null
  fi
  # 此时 job_instance 的 FK 已断,trigger_request / outbox 安全删除
  # CRON PROBE job (§9.5) auto-fire 的 trigger_request request_id 自动生成不带 PROBE_TAG,
  # 必须 OR 按 job_code 兜底,否则 fire 的 SCHEDULED 行漏清
  # trigger_outbox_event 表无 job_code 列,只走 request_id pattern (CRON fire 直接写 trigger_request 不入 outbox)
  psql_q "DELETE FROM batch.trigger_outbox_event WHERE request_id LIKE '$pattern'" >/dev/null
  psql_q "DELETE FROM batch.trigger_request WHERE request_id LIKE '$pattern' OR job_code LIKE '$pattern'" >/dev/null
  # CRON PROBE 在 trigger_runtime_state 留行,不清会一直 fire 污染下次 run
  psql_q "DELETE FROM batch.trigger_runtime_state WHERE job_definition_id IN (SELECT id FROM batch.job_definition WHERE job_code LIKE '$pattern')" >/dev/null
  psql_q "DELETE FROM batch.workflow_node WHERE node_code='SEEDVAL_PROBE'" >/dev/null
  psql_q "DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN (SELECT id FROM batch.pipeline_definition WHERE job_code LIKE '$pattern')" >/dev/null
  psql_q "DELETE FROM batch.pipeline_definition WHERE job_code LIKE '$pattern'" >/dev/null
  # STRICT EXPORT 写的 file_record + 我们 seed 的 settlement_batch 一起清(按 PROBE_TAG batch_no)
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business -tAc "DELETE FROM biz.settlement_batch WHERE batch_no LIKE '$pattern'" >/dev/null 2>&1 || true
  # STRICT DISPATCH PROBE file_record (按 file_code = PROBE_TAG-file)
  psql_q "DELETE FROM batch.file_audit_log WHERE file_id IN (SELECT id FROM batch.file_record WHERE file_code LIKE '$pattern' OR source_ref LIKE '$pattern')" >/dev/null
  psql_q "DELETE FROM batch.file_dispatch_record WHERE file_id IN (SELECT id FROM batch.file_record WHERE file_code LIKE '$pattern')" >/dev/null
  psql_q "DELETE FROM batch.file_record WHERE source_ref LIKE '$pattern' OR file_code LIKE '$pattern'" >/dev/null
  # IMPORT 写的 biz.customer_account 行(SEEDVAL_C* 前缀, IMPORT 真 SUCCESS 后产生)
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business -tAc "DELETE FROM biz.customer_account WHERE customer_no LIKE 'SEEDVAL_%'" >/dev/null 2>&1 || true
  # PROBE job_definition (§9.5 CRON 真触发探针等);
  # V67 trigger_runtime_state ON DELETE CASCADE 自动清
  psql_q "DELETE FROM batch.job_definition WHERE job_code LIKE '$pattern'" >/dev/null
}

# 退出钩子: 任何路径退出都跑 sweep, 杜绝 mid-run crash 留垃圾
on_exit() {
  local rc=$?
  do_cleanup "$SWEEP_PATTERN" 2>/dev/null || true
  exit $rc
}
trap on_exit EXIT

# http_post path body [header1] [header2] ...
# 把 header 当独立参数传,避免 quote 展开陷阱
http_post() {
  local path="$1"; shift
  local body="$1"; shift
  local args=(-sS -o /tmp/resp.body -w "%{http_code}" -X POST
    "http://localhost:${TRIGGER_PORT}${path}"
    -H "Content-Type: application/json"
    -H "X-Internal-Secret: $INTERNAL_SECRET")
  for h in "$@"; do
    args+=(-H "$h")
  done
  args+=(-d "$body")
  curl "${args[@]}" 2>/dev/null
}

# ---------- 0. 探活 ----------
section "0. 探活"

if curl -sf "http://localhost:${TRIGGER_PORT}/actuator/health" >/dev/null 2>&1; then
  result pass "trigger UP" "port $TRIGGER_PORT"
else
  result fail "trigger UP" "port $TRIGGER_PORT 不响应"
  echo -e "\n${RED}前置条件失败,中止。请先 ./scripts/docker/up-apps.sh 或 ./scripts/local/start-all.sh${NC}"
  exit 1
fi

if curl -sf "http://localhost:${ORCH_PORT}/actuator/health" >/dev/null 2>&1; then
  result pass "orchestrator UP" "port $ORCH_PORT"
else
  result fail "orchestrator UP" "port $ORCH_PORT 不响应"
  exit 1
fi

if docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "SELECT 1" >/dev/null 2>&1; then
  result pass "postgres 可达" "$PG_CONTAINER"
else
  result fail "postgres 可达" "$PG_CONTAINER 容器不存在或拒绝连接"
  exit 1
fi

# ---------- 0.3. STRICT 前置：四类 worker（默认 default-tenant，端口同 start-all.sh） ----------
if [[ "$STRICT" == "1" ]]; then
  section "0.3 STRICT 前置: worker(import/export/dispatch/process) + default-tenant"
  _W_IMP="${BATCH_WORKER_IMPORT_PORT:-18083}"
  _W_EXP="${BATCH_WORKER_EXPORT_PORT:-18084}"
  _W_DISP="${BATCH_WORKER_DISPATCH_PORT:-18085}"
  _W_PROC="${BATCH_WORKER_PROCESS_PORT:-18086}"
  _strict_workers_ok=1
  for _pair in "import:${_W_IMP}" "export:${_W_EXP}" "dispatch:${_W_DISP}" "process:${_W_PROC}"; do
    _name="${_pair%%:*}"
    _port="${_pair##*:}"
    if curl -sf "http://localhost:${_port}/actuator/health" >/dev/null 2>&1; then
      result pass "worker-${_name} UP" "port ${_port}（与各模块默认 BATCH_WORKER_*_TENANT_ID=default-tenant 一致）"
    else
      result fail "worker-${_name} UP" "port ${_port} 无响应 — 请 START_WORKERS=1 bash scripts/local/start-all.sh（勿单独改租户为 ta/tb/tc）"
      _strict_workers_ok=0
    fi
  done
  if [[ "${_strict_workers_ok:-0}" -eq 0 ]]; then
    echo -e "\n${RED}STRICT=1 需要四类 JVM worker 在线且默认注册 default-tenant；修正后重跑本脚本。${NC}"
    exit 1
  fi
fi

# ---------- 0.4. PRE_CLEANUP — 跑前清所有 seedval-* 历史残留(避免污染) ----------
if [[ "$PRE_CLEANUP" == "1" ]]; then
  pre_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN'")
  if [[ "$pre_cnt" -gt 0 ]]; then
    do_cleanup "$SWEEP_PATTERN"
    after_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN'")
    result pass "PRE_CLEANUP" "扫 $pre_cnt 行历史 seedval-* trigger_request, 清理后剩 $after_cnt 行"
  else
    result pass "PRE_CLEANUP" "无历史残留, 跳过"
  fi
fi

# ---------- 0.5. 可选 reload 种子 ----------
if [[ "$LOAD_SEED" == "1" ]]; then
  section "0.5. 重载种子"
  for f in platform_seed.sql platform_edge_cases.sql; do
    docker cp "$ROOT/scripts/db/test-seed/$f" "$PG_CONTAINER:/tmp/$f" >/dev/null
    if docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -f "/tmp/$f" >/dev/null 2>&1; then
      result pass "seed reload" "$f"
    else
      result fail "seed reload" "$f 失败"
    fi
  done
  for f in business_seed.sql business_edge_cases.sql; do
    docker cp "$ROOT/scripts/db/test-seed/$f" "$PG_CONTAINER:/tmp/$f" >/dev/null
    if docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business -f "/tmp/$f" >/dev/null 2>&1; then
      result pass "biz seed reload" "$f"
    else
      result fail "biz seed reload" "$f 失败"
    fi
  done
fi

# ---------- 1. Schema 落地 ----------
section "1. Schema 落地(V82-V85)"

for v in 82 83 84 85; do
  ok=$(psql_q "SELECT success FROM batch.flyway_schema_history WHERE version='$v'")
  if [[ "$ok" == "t" ]]; then
    result pass "V$v migration" "applied success=t"
  else
    result fail "V$v migration" "未应用或 success=f (got=$ok)"
  fi
done

# V82 约束类型
v82_def=$(psql_q "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='batch.job_step_instance'::regclass AND conname='uk_job_step_instance_task'")
if [[ "$v82_def" == "UNIQUE (tenant_id, job_task_id)" ]]; then
  result pass "V82 约束定义" "$v82_def"
else
  result fail "V82 约束定义" "got=$v82_def"
fi

# V83 类型
v83_type=$(psql_q "SELECT contype FROM pg_constraint WHERE conrelid='batch.trigger_outbox_event'::regclass AND conname='uk_trigger_outbox_event_tenant_request'")
if [[ "$v83_type" == "u" ]]; then
  result pass "V83 是 CONSTRAINT" "(原 V80 是 INDEX, V83 已升级)"
else
  result fail "V83 contype" "got=$v83_type 期望 u"
fi

# V84/V85 列存在
for tbl in workflow_node workflow_edge; do
  has_col=$(psql_q "SELECT count(*) FROM information_schema.columns WHERE table_schema='batch' AND table_name='$tbl' AND column_name='tenant_id'")
  if [[ "$has_col" == "1" ]]; then
    result pass "$tbl.tenant_id 列存在" "V8x 已 backfill"
  else
    result fail "$tbl.tenant_id 列" "缺失"
  fi
done

# ---------- 2. 种子数据基线 ----------
section "2. 种子数据基线"

job_count=$(psql_q "SELECT count(*) FROM batch.job_definition WHERE tenant_id IN ('default-tenant','tenant-finance')")
if [[ "$job_count" -ge 3 ]]; then
  result pass "job_definition 基线" "$job_count 行(default-tenant + tenant-finance)"
else
  result fail "job_definition 基线" "仅 $job_count 行,期望 >=3,LOAD_SEED=1 强制重载"
fi

wf_node_count=$(psql_q "SELECT count(*) FROM batch.workflow_node WHERE tenant_id='tenant-finance'")
if [[ "$wf_node_count" -ge 4 ]]; then
  result pass "workflow_node 基线" "$wf_node_count 行(tenant-finance/finance_recon_flow)"
else
  result fail "workflow_node 基线" "$wf_node_count 行,期望 >=4"
fi

# ---------- 3. V84 多租隔离行为 ----------
section "3. V84 多租隔离行为(workflow_node)"

# 取 finance 那行作"基准"node_code
finance_wf_id=$(psql_q "SELECT id FROM batch.workflow_definition WHERE tenant_id='tenant-finance' AND workflow_code='finance_recon_flow' LIMIT 1")
if [[ -z "$finance_wf_id" ]]; then
  result skip "V84 跨租户隔离" "tenant-finance/finance_recon_flow 不在种子中,跳过"
else
  # 探针:default-tenant 用同一 wf_def_id + 同一 node_code 应能共存
  cleanup_sql="DELETE FROM batch.workflow_node WHERE tenant_id='default-tenant' AND workflow_definition_id=$finance_wf_id AND node_code='SEEDVAL_PROBE'"
  psql_q "$cleanup_sql" >/dev/null
  insert1=$(psql_w_first "INSERT INTO batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, retry_policy, retry_max_count, timeout_seconds, enabled) VALUES ('default-tenant', $finance_wf_id, 'SEEDVAL_PROBE', 'probe', 'TASK', 99, 'NONE', 0, 0, true) ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING RETURNING tenant_id")
  insert2=$(psql_w_first "INSERT INTO batch.workflow_node (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, retry_policy, retry_max_count, timeout_seconds, enabled) VALUES ('default-tenant', $finance_wf_id, 'SEEDVAL_PROBE', 'probe', 'TASK', 99, 'NONE', 0, 0, true) ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING RETURNING tenant_id")
  if [[ "$insert1" == "default-tenant" && -z "$insert2" ]]; then
    result pass "V84 跨租户可共存 + 同租户唯一" "default-tenant 插入成功,重插被 ON CONFLICT 拦"
  else
    result fail "V84 隔离行为" "1st=$insert1 2nd=$insert2 (期望 default-tenant + 空)"
  fi
  psql_q "$cleanup_sql" >/dev/null
fi

# ---------- 4. 同步 API: trigger fire happy path ----------
section "4. 同步 API: 写 trigger_request + trigger_outbox_event"

REQUEST_ID="${PROBE_TAG}-import-happy"
http_code=$(http_post "/api/triggers/launch" \
  "{\"tenantId\":\"default-tenant\",\"jobCode\":\"import_customer_job\",\"bizDate\":\"2026-05-03\",\"triggerType\":\"API\",\"params\":{\"templateCode\":\"import_customer_v1\",\"content\":\"[]\"}}" \
  "Idempotency-Key: $REQUEST_ID" "X-Request-Id: $REQUEST_ID")
if [[ "$http_code" == "200" ]]; then
  result pass "fire 同步返回" "HTTP 200"
else
  body=$(cat /tmp/resp.body)
  result fail "fire 同步返回" "HTTP $http_code body=$body"
fi

# 立刻验 trigger_request + trigger_outbox_event 写入(用 request_id 定位)
got_req=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID'")
got_outbox=$(psql_q "SELECT count(*) FROM batch.trigger_outbox_event WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID'")
if [[ "$got_req" == "1" && "$got_outbox" == "1" ]]; then
  result pass "同事务双写" "trigger_request=1 trigger_outbox_event=1"
else
  result fail "同事务双写" "trigger_request=$got_req trigger_outbox_event=$got_outbox(期望 1/1)"
fi

# ---------- 5. ADR-010 异步链路 ----------
section "5. ADR-010 异步链路: outbox → Kafka → orchestrator → job_instance"

elapsed=0
launched_id=""
while [[ $elapsed -lt $AWAIT_TIMEOUT ]]; do
  launched_id=$(psql_q "SELECT related_job_instance_id FROM batch.trigger_request WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID' AND request_status='LAUNCHED'")
  if [[ -n "$launched_id" ]]; then
    break
  fi
  sleep 1
  elapsed=$((elapsed+1))
done

if [[ -n "$launched_id" ]]; then
  outbox_status=$(psql_q "SELECT publish_status FROM batch.trigger_outbox_event WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID'")
  result pass "TriggerOutboxRelay 推 LAUNCHED" "${elapsed}s, job_instance_id=$launched_id, outbox=$outbox_status"
else
  outbox_state=$(psql_q "SELECT publish_status||'/attempt='||publish_attempt FROM batch.trigger_outbox_event WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID'")
  req_state=$(psql_q "SELECT request_status FROM batch.trigger_request WHERE tenant_id='default-tenant' AND request_id='$REQUEST_ID'")
  result fail "TriggerOutboxRelay 推 LAUNCHED" "${AWAIT_TIMEOUT}s 未推到; trigger_request=$req_state outbox=$outbox_state(local profile lazy=true 会卡这一步,docker 应通)"
fi

# ---------- 6. 异常: 缺必填字段 ----------
section "6. 异常路径"

REQUEST_ID="${PROBE_TAG}-bad-payload"
http_code=$(http_post "/api/triggers/launch" \
  '{"tenantId":"default-tenant","triggerType":"API","bizDate":"2026-05-03"}' \
  "Idempotency-Key: $REQUEST_ID")
if [[ "$http_code" == "400" ]]; then
  result pass "缺 jobCode → 400" "validation 拒绝"
else
  body=$(cat /tmp/resp.body | head -c 100)
  result fail "缺 jobCode" "HTTP $http_code(期望 400) body=$body"
fi

# 7. 异常: 缺幂等键
http_code=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
  -X POST "http://localhost:${TRIGGER_PORT}/api/triggers/launch" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Secret: $INTERNAL_SECRET" \
  -d '{"tenantId":"default-tenant","jobCode":"import_customer_job","bizDate":"2026-05-03","triggerType":"API"}' 2>/dev/null)
if [[ "$http_code" == "400" ]]; then
  result pass "缺 Idempotency-Key → 400" "MISSING_IDEMPOTENCY_KEY"
else
  body=$(cat /tmp/resp.body | head -c 100)
  result fail "缺 Idempotency-Key" "HTTP $http_code body=$body"
fi

# 8. 异常: 缺 X-Internal-Secret
http_code=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
  -X POST "http://localhost:${TRIGGER_PORT}/api/triggers/launch" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${PROBE_TAG}-no-secret" \
  -d '{"tenantId":"default-tenant","jobCode":"import_customer_job","bizDate":"2026-05-03","triggerType":"API"}' 2>/dev/null)
if [[ "$http_code" == "401" ]]; then
  result pass "缺 X-Internal-Secret → 401" "InternalSecretFilter 拦截"
elif [[ "$http_code" == "200" ]]; then
  result pass "缺 X-Internal-Secret → 200" "(bypass-mode=true,本地调试模式)"
else
  body=$(cat /tmp/resp.body | head -c 100)
  result fail "缺 X-Internal-Secret" "HTTP $http_code body=$body(期望 401 或 bypass)"
fi

# 9. 异常: 同 Idempotency-Key 重发(幂等回放)
DEDUP_REQ_ID="${PROBE_TAG}-dedup"
http_post "/api/triggers/launch" \
  "{\"tenantId\":\"default-tenant\",\"jobCode\":\"import_customer_job\",\"bizDate\":\"2026-05-03\",\"triggerType\":\"API\",\"params\":{\"templateCode\":\"import_customer_v1\",\"content\":\"[]\"}}" \
  "Idempotency-Key: $DEDUP_REQ_ID" "X-Request-Id: $DEDUP_REQ_ID" >/dev/null
sleep 1
http_post "/api/triggers/launch" \
  "{\"tenantId\":\"default-tenant\",\"jobCode\":\"import_customer_job\",\"bizDate\":\"2026-05-03\",\"triggerType\":\"API\",\"params\":{\"templateCode\":\"import_customer_v1\",\"content\":\"[]\"}}" \
  "Idempotency-Key: $DEDUP_REQ_ID" "X-Request-Id: $DEDUP_REQ_ID" >/dev/null
dedup_count=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE tenant_id='default-tenant' AND request_id='$DEDUP_REQ_ID'")
if [[ "$dedup_count" == "1" ]]; then
  result pass "幂等键重发去重" "2 次 fire 仅 1 行 trigger_request"
else
  result fail "幂等键重发去重" "$dedup_count 行 trigger_request(期望 1)"
fi

# 10. 异常: 跨租户 launch(default-tenant 用 tenant-finance 的 jobCode)
CROSS_REQ_ID="${PROBE_TAG}-cross-tenant"
http_code=$(http_post "/api/triggers/launch" \
  "{\"tenantId\":\"default-tenant\",\"jobCode\":\"finance_recon_workflow\",\"bizDate\":\"2026-05-03\",\"triggerType\":\"API\"}" \
  "Idempotency-Key: $CROSS_REQ_ID" "X-Request-Id: $CROSS_REQ_ID")
# trigger 端是 "宽容前端" — HTTP 200 是预期(只写 outbox);orch 消费时按 (tenantId, jobCode)
# 查 job_definition 找不到会拒绝;trigger_request 应停在 ACCEPTED 不进 LAUNCHED
if [[ "$http_code" == "200" ]]; then
  sleep 8  # 等 relay + orch 消费
  cross_status=$(psql_q "SELECT request_status FROM batch.trigger_request WHERE tenant_id='default-tenant' AND request_id='$CROSS_REQ_ID'")
  case "$cross_status" in
    REJECTED|FAILED|GIVE_UP|ACCEPTED)
      result pass "跨租户 jobCode → 后端拒绝" "trigger HTTP 200(异步设计) + trigger_request=$cross_status(未推 LAUNCHED)" ;;
    LAUNCHED)
      result fail "跨租户 jobCode 隔离" "trigger_request=LAUNCHED — 多租隔离失效!" ;;
    *)
      result fail "跨租户 jobCode 隔离" "trigger_request=$cross_status(未知状态)" ;;
  esac
elif [[ "$http_code" == "404" || "$http_code" == "400" ]]; then
  result pass "跨租户 jobCode → 同步拒绝" "HTTP $http_code(trigger 端校验)"
else
  body=$(cat /tmp/resp.body | head -c 100)
  result fail "跨租户 jobCode 行为" "HTTP $http_code body=$body"
fi

# ---------- 7. Worker 链路覆盖(各 worker 类型 + workflow_type) ----------
# 这一节是"链路覆盖"而非严格 happy path:
#   PASS 标准 = trigger_request 推到 LAUNCHED 且 job_instance 创建(ADR-010 链路通)
#   终态(SUCCESS / FAILED / 还在 CREATED 等待 worker claim)都算 PASS — 这一节验证
#   的是 "fire → outbox → Kafka → orch → instance 写表" 这段;worker terminal state
#   依赖业务数据完整性(biz 表 / MinIO 文件 / 模板加密 key 等),非本脚本目标。
#   严格的 worker SUCCESS 验证看 batch-e2e-tests 模块。

# fire_and_await tenant jobCode params_json timeout_sec
# 输出全局: LAST_FIRE_DETAIL
# 返回: 0=task 已离开 CREATED(链路通),1=未推 LAUNCHED(异步链路卡)
fire_and_await() {
  local tenant="$1"; local jobCode="$2"; local params_json="$3"; local timeout="${4:-25}"
  local req_id="${PROBE_TAG}-${tenant}-${jobCode}"
  local body="{\"tenantId\":\"$tenant\",\"jobCode\":\"$jobCode\",\"bizDate\":\"2026-05-03\",\"triggerType\":\"API\",\"params\":$params_json}"
  http_post "/api/triggers/launch" "$body" \
    "Idempotency-Key: $req_id" "X-Request-Id: $req_id" >/dev/null

  local elapsed=0 job_id=""
  while [[ $elapsed -lt $timeout ]]; do
    job_id=$(psql_q "SELECT related_job_instance_id FROM batch.trigger_request WHERE tenant_id='$tenant' AND request_id='$req_id' AND request_status='LAUNCHED'")
    [[ -n "$job_id" ]] && break
    sleep 1; elapsed=$((elapsed+1))
  done
  if [[ -z "$job_id" ]]; then
    local req_status; req_status=$(psql_q "SELECT request_status FROM batch.trigger_request WHERE tenant_id='$tenant' AND request_id='$req_id'")
    LAST_FIRE_DETAIL="${timeout}s 未推 LAUNCHED, trigger_request=$req_status"
    return 1
  fi

  # 等 instance 推到终态(SUCCESS / FAILED / PARTIAL_FAILED / TERMINATED / CANCELLED)
  local instance_status=""
  while [[ $elapsed -lt $timeout ]]; do
    instance_status=$(psql_q "SELECT instance_status FROM batch.job_instance WHERE id=$job_id")
    case "$instance_status" in
      SUCCESS|FAILED|PARTIAL_FAILED|TERMINATED|CANCELLED) break ;;
    esac
    sleep 2; elapsed=$((elapsed+2))
  done

  local task_status; task_status=$(psql_q "SELECT string_agg(DISTINCT task_status, ',') FROM batch.job_task WHERE tenant_id='$tenant' AND job_instance_id=$job_id")
  local err; err=$(psql_q "SELECT string_agg(DISTINCT coalesce(error_code,'') || ':' || coalesce(error_message,''), ' | ') FROM batch.job_task WHERE job_instance_id=$job_id AND error_code IS NOT NULL")
  LAST_FIRE_DETAIL="job_id=$job_id instance=$instance_status tasks=${task_status:-none} err=${err:-none} (${elapsed}s)"
  case "$instance_status" in
    SUCCESS) return 0 ;;                         # 严格 happy
    FAILED|PARTIAL_FAILED) return 2 ;;           # 终态但失败 — 业务/数据问题, 不算 link 通
    *) return 3 ;;                               # 仍未到终态 — worker 没消费/卡住
  esac
}

# assert_fire label tenant jobCode params [expect=success|terminal|reach_worker]
# expect 默认 success(严格 happy);也可放宽到 terminal(SUCCESS 或 FAILED 都接受)/reach_worker(只要 instance 创建)
assert_fire() {
  local label="$1"; local tenant="$2"; local jobCode="$3"; local params="$4"; local expect="${5:-success}"
  fire_and_await "$tenant" "$jobCode" "$params" "$AWAIT_TIMEOUT"
  local rc=$?
  case "$expect" in
    success)
      [[ $rc -eq 0 ]] && result pass "$label [SUCCESS]" "$LAST_FIRE_DETAIL" || result fail "$label [需 SUCCESS]" "$LAST_FIRE_DETAIL"
      ;;
    terminal)
      [[ $rc -eq 0 || $rc -eq 2 ]] && result pass "$label [终态]" "$LAST_FIRE_DETAIL" || result fail "$label [未到终态]" "$LAST_FIRE_DETAIL"
      ;;
    *)
      [[ $rc -ne 1 ]] && result pass "$label [link]" "$LAST_FIRE_DETAIL" || result fail "$label [link 断]" "$LAST_FIRE_DETAIL"
      ;;
  esac
}

if [[ "$STRICT" == "1" ]]; then
  # ===== STRICT=1: 严格 SUCCESS 模式 — 只用 default-tenant(唯一有 ONLINE worker 的租户)
  # 限制: ta/tb/tc 在 multi-tenant-seed 里 worker_registry 全 OFFLINE,
  #       它们的 jobs 永远不会被 worker 选中,严格 SUCCESS 必然 FAIL → 不纳入 STRICT 集
  # 推荐: AWAIT_TIMEOUT=90 给真 SUCCESS 留时间
  section "7. Worker 严格 happy [STRICT=1]: fire → instance 推到 SUCCESS"

  assert_fire "IMPORT 严格 (default-tenant, JSON 不加密)" "default-tenant" "import_customer_job" \
    '{"templateCode":"import_customer_json_v1","content":"[{\"customerNo\":\"SEEDVAL_C001\",\"customerName\":\"smoke probe\",\"customerType\":\"PERSONAL\"}]"}' \
    success

  # EXPORT/WORKFLOW PIPELINE 用 PROBE_TAG 唯一 batch 避免跨 run file_record checksum 冲突
  PROBE_BATCH_NO="${PROBE_TAG}-BATCH"
  PROBE_BIZDATE=$(date +%Y-%m-%d)
  # 先 seed 一个 settlement_batch 行供 EXPORT loadBatch 命中(注意 biz 表在 batch_business DB)
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d batch_business -tAc \
    "INSERT INTO biz.settlement_batch (tenant_id, batch_no, biz_date, accounting_period, snapshot_mode, snapshot_ts, batch_status) VALUES ('default-tenant', '$PROBE_BATCH_NO', CURRENT_DATE, to_char(CURRENT_DATE, 'YYYY-MM'), 'BATCH', now(), 'READY') ON CONFLICT (tenant_id, batch_no) DO NOTHING" >/dev/null 2>&1 || true

  assert_fire "EXPORT 严格 (default-tenant, settlement)" "default-tenant" "export_settlement_job" \
    "{\"templateCode\":\"export_settlement_v1\",\"batchNo\":\"$PROBE_BATCH_NO\",\"bizDate\":\"$PROBE_BIZDATE\"}" success

  assert_fire "WORKFLOW PIPELINE 严格 (default-tenant)" "default-tenant" "wf_probe_pipeline" \
    "{\"templateCode\":\"export_settlement_v1\",\"batchNo\":\"$PROBE_BATCH_NO\",\"bizDate\":\"$PROBE_BIZDATE\"}" success

  # ===== DISPATCH 严格 =====
  # 自 seed: PROBE_TAG file_record (LOCAL 占位) + default-tenant DISPATCH job + fire
  # local_dispatch channel 只写 envelope JSON 到 /tmp/batch/local-dispatch, 不需源文件
  PROBE_DISPATCH_JOB="${PROBE_TAG}-dispatch"
  PROBE_FILE_CODE="${PROBE_TAG}-file"
  psql_q "INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, biz_type,
      schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,
      priority, enabled, created_at, updated_at
    ) VALUES (
      'default-tenant', '$PROBE_DISPATCH_JOB', 'seedval dispatch probe', 'DISPATCH', 'TEST',
      'MANUAL', 'Asia/Shanghai', 'SCHEDULED', 'dispatch_queue', 'DISPATCH', 'always_open',
      5, true, now(), now()
    ) ON CONFLICT (tenant_id, job_code) DO UPDATE SET enabled=true" >/dev/null
  probe_file_id=$(psql_w_first "INSERT INTO batch.file_record (
      tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
      file_format_type, charset, file_size_bytes, checksum_type, checksum_value,
      storage_type, storage_path, storage_bucket, file_status, biz_date, source_type, source_ref
    ) VALUES (
      'default-tenant', '$PROBE_FILE_CODE', 'TEST', 'OUTPUT', '${PROBE_TAG}-probe.txt', '${PROBE_TAG}-probe.txt',
      'JSON', 'UTF-8', 12, 'NONE', 'noop',
      'LOCAL', '/tmp/batch/${PROBE_TAG}-probe.txt', 'batch-dev', 'GENERATED', CURRENT_DATE, 'GENERATED', '$PROBE_TAG'
    ) RETURNING id")
  assert_fire "DISPATCH 严格 (default-tenant, local channel)" "default-tenant" "$PROBE_DISPATCH_JOB" \
    "{\"fileId\":\"$probe_file_id\",\"channelCode\":\"local_dispatch\"}" success

  # ===== PROCESS 严格 =====
  # seed default-tenant PROCESS job + pipeline steps; COMPUTE step 走 sqlTransformCompute,
  # 写入 biz.customer_account 的 SEEDVAL_* probe 行,可被 do_cleanup 清掉。
  PROBE_PROCESS_JOB="${PROBE_TAG}-process"
  psql_q "INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, biz_type,
      schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,
      priority, enabled, created_at, updated_at
    ) VALUES (
      'default-tenant', '$PROBE_PROCESS_JOB', 'seedval process probe', 'PROCESS', 'TEST',
      'MANUAL', 'Asia/Shanghai', 'SCHEDULED', 'process_queue', 'PROCESS', 'always_open',
      5, true, now(), now()
    ) ON CONFLICT (tenant_id, job_code) DO UPDATE SET enabled=true" >/dev/null
  probe_process_pipeline_id=$(psql_w_first "INSERT INTO batch.pipeline_definition (
      tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
      version, enabled, description, created_at, updated_at
    ) VALUES (
      'default-tenant', '$PROBE_PROCESS_JOB', 'seedval process probe pipeline',
      'PROCESS', 'TEST', 'PROCESS', 1, true, 'seed validation process probe', now(), now()
    ) RETURNING id")
  psql_q "INSERT INTO batch.pipeline_step_definition (
      pipeline_definition_id, step_code, step_name, stage_code, step_order,
      impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
      enabled, created_at, updated_at
    ) VALUES
      ($probe_process_pipeline_id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
       'PROCESS_PREPARE', '{}'::jsonb, 300, 'NONE', 0, true, now(), now()),
      ($probe_process_pipeline_id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
       'sqlTransformCompute',
       jsonb_build_object(
         'sqlTransformCompute', jsonb_build_object(
           'sourceSql',
           'select ' ||
           quote_literal('default-tenant') || '::text as tenant_id, ' ||
           quote_literal('SEEDVAL_' || upper(replace('$PROBE_TAG', '-', '_'))) || '::text as customer_no, ' ||
           quote_literal('Seed Validation Process Probe') || '::text as customer_name, ' ||
           quote_literal('PERSONAL') || '::text as customer_type, ' ||
           quote_literal('ACTIVE') || '::text as status',
           'targetSchema', 'biz',
           'targetTable', 'customer_account',
           'writeMode', 'UPSERT',
           'columns', jsonb_build_array(
             jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
             jsonb_build_object('source', 'customer_no', 'target', 'customer_no'),
             jsonb_build_object('source', 'customer_name', 'target', 'customer_name'),
             jsonb_build_object('source', 'customer_type', 'target', 'customer_type'),
             jsonb_build_object('source', 'status', 'target', 'status')
           ),
           'conflictColumns', jsonb_build_array('tenant_id', 'customer_no'),
           'validations', jsonb_build_array(
             jsonb_build_object(
               'name', 'staged_one_row',
               'checkSql', 'select count(*) = 1 as pass, ''expected one staged row'' as message from batch.process_staging where batch_key = :batchKey'
             )
           ),
           'emptyResultPolicy', 'FAIL',
           'maxStagedRows', 10
         )
       ), 600, 'FIXED', 1, true, now(), now()),
      ($probe_process_pipeline_id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
       'PROCESS_VALIDATE', '{}'::jsonb, 300, 'NONE', 0, true, now(), now()),
      ($probe_process_pipeline_id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
       'PROCESS_COMMIT', '{}'::jsonb, 300, 'NONE', 0, true, now(), now()),
      ($probe_process_pipeline_id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
       'PROCESS_FEEDBACK', '{}'::jsonb, 300, 'NONE', 0, true, now(), now())" >/dev/null
  assert_fire "PROCESS 严格 (default-tenant, sqlTransformCompute)" "default-tenant" "$PROBE_PROCESS_JOB" \
    "{\"bizDate\":\"$(date +%Y-%m-%d)\",\"batchKey\":\"$PROBE_TAG-batch\"}" success
else
  # ===== 默认: reach_worker 模式 — 覆盖广(7 场景含 4 worker × 4 workflow_type),
  #            只验 ADR-010 链路通 + instance 创建,worker 终态不强求
  section "7. Worker 链路覆盖 [STRICT=0]: fire → instance 创建即 PASS"

  assert_fire "IMPORT 链路 (tb, XML 模板)" "tb" "TB_IMPORT_TRANSACTION" \
    '{"templateCode":"IMP-TXN-XML","content":"<txns><txn><txnNo>T001</txnNo><accountNo>A001</accountNo><txnType>DEBIT</txnType><amount>100.00</amount><currencyCode>CNY</currencyCode><txnDate>2026-05-03</txnDate></txn></txns>"}' \
    reach_worker

  assert_fire "EXPORT 链路 (tc, risk_alert)" "tc" "TC_EXPORT_RISK_ALERT" '{}' reach_worker
  assert_fire "DISPATCH 链路 (tc, local channel)" "tc" "TC_DISPATCH_REVIEW" '{"channelCode":"tc_local_archive"}' reach_worker
  assert_fire "WORKFLOW DAG (ta)" "ta" "TA_WF_SETTLEMENT" '{}' reach_worker
  assert_fire "WORKFLOW PIPELINE (tc)" "tc" "TC_WF_RISK_PIPELINE" '{}' reach_worker
  assert_fire "WORKFLOW DAG GATEWAY (probe)" "default-tenant" "wf_probe_gateway" '{}' reach_worker
  assert_fire "WORKFLOW MIXED (probe + ADR-009 DSL)" "default-tenant" "wf_probe_mixed" '{}' reach_worker
fi

# ---------- 8. 多租户并发隔离实跑 ----------
section "8. 多租户并发: 同时 fire ta + tb,验证不串"

REQ_TA="${PROBE_TAG}-mt-ta"
REQ_TB="${PROBE_TAG}-mt-tb"
http_post "/api/triggers/launch" \
  '{"tenantId":"ta","jobCode":"TA_IMPORT_CUSTOMER","bizDate":"2026-05-03","triggerType":"API","params":{}}' \
  "Idempotency-Key: $REQ_TA" "X-Request-Id: $REQ_TA" >/dev/null &
http_post "/api/triggers/launch" \
  '{"tenantId":"tb","jobCode":"TB_EXPORT_STATEMENT","bizDate":"2026-05-03","triggerType":"API","params":{}}' \
  "Idempotency-Key: $REQ_TB" "X-Request-Id: $REQ_TB" >/dev/null &
wait

# 等两个都推 LAUNCHED
elapsed=0
ta_id=""; tb_id=""
while [[ $elapsed -lt $AWAIT_TIMEOUT ]]; do
  [[ -z "$ta_id" ]] && ta_id=$(psql_q "SELECT related_job_instance_id FROM batch.trigger_request WHERE tenant_id='ta' AND request_id='$REQ_TA' AND request_status='LAUNCHED'")
  [[ -z "$tb_id" ]] && tb_id=$(psql_q "SELECT related_job_instance_id FROM batch.trigger_request WHERE tenant_id='tb' AND request_id='$REQ_TB' AND request_status='LAUNCHED'")
  [[ -n "$ta_id" && -n "$tb_id" ]] && break
  sleep 1; elapsed=$((elapsed+1))
done

if [[ -n "$ta_id" && -n "$tb_id" ]]; then
  # 关键断言:两个 job_instance 各自 tenant_id 隔离
  ta_tenant=$(psql_q "SELECT tenant_id FROM batch.job_instance WHERE id=$ta_id")
  tb_tenant=$(psql_q "SELECT tenant_id FROM batch.job_instance WHERE id=$tb_id")
  if [[ "$ta_tenant" == "ta" && "$tb_tenant" == "tb" ]]; then
    result pass "多租并发 → 隔离" "ta job_id=$ta_id (tenant=$ta_tenant) + tb job_id=$tb_id (tenant=$tb_tenant) (${elapsed}s)"
  else
    result fail "多租并发隔离" "ta_tenant=$ta_tenant tb_tenant=$tb_tenant — 数据串了!"
  fi
else
  result fail "多租并发 推 LAUNCHED" "ta_id=$ta_id tb_id=$tb_id (${AWAIT_TIMEOUT}s)"
fi

# ---------- 9. ADVANCED gate: orch 运维 API + console auth + trigger cron ----------
# ADVANCED=1 才跑;默认跳过(避免常规 smoke 跑得太重)
if [[ "$ADVANCED" == "1" ]]; then
  section "9. Advanced (ADVANCED=1): orch /internal/* + console auth + trigger cron"

  # 9.1 Outbox cleanup smoke
  resp=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
    -X POST "http://localhost:${ORCH_PORT}/internal/outbox/cleanup?tenantId=ta&retainDays=99999" \
    -H "X-Internal-Secret: $INTERNAL_SECRET" 2>/dev/null)
  if [[ "$resp" == "200" ]]; then
    body=$(cat /tmp/resp.body)
    result pass "Outbox cleanup API" "HTTP 200 $body"
  else
    result fail "Outbox cleanup API" "HTTP $resp body=$(cat /tmp/resp.body | head -c 80)"
  fi

  # 9.2 Outbox republish smoke (空列表,验证接口可达 + 返回 reset=0)
  resp=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
    -X POST "http://localhost:${ORCH_PORT}/internal/outbox/republish?tenantId=ta" \
    -H "X-Internal-Secret: $INTERNAL_SECRET" \
    -H "Content-Type: application/json" \
    -d '{"ids":[]}' 2>/dev/null)
  if [[ "$resp" == "200" ]]; then
    body=$(cat /tmp/resp.body)
    if echo "$body" | grep -q '"reset":0'; then
      result pass "Outbox republish API" "HTTP 200 $body (空列表 reset=0)"
    else
      result fail "Outbox republish API" "HTTP 200 但 body 异常: $body"
    fi
  else
    result fail "Outbox republish API" "HTTP $resp body=$(cat /tmp/resp.body | head -c 80)"
  fi

  # 9.3 Compensate submit (使用合法 type=JOB + 不存在的 instance, 期望 4xx 业务错误而非 500)
  # 注意: 已知 bug — DefaultCompensationService 的 audit log 用 'COMPENSATION_REJECTED' log_type
  # 但 V13 ck_job_execution_log_type CHECK 约束不含此值,导致 reject 路径会回 500 而不是 4xx
  resp=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
    -X POST "http://localhost:${ORCH_PORT}/internal/compensations" \
    -H "X-Internal-Secret: $INTERNAL_SECRET" \
    -H "Content-Type: application/json" \
    -d '{"tenantId":"ta","compensationType":"JOB","jobCode":"TA_IMPORT_CUSTOMER","bizDate":"2026-05-03","operatorId":"seedval","reason":"smoke probe","traceId":"'"${PROBE_TAG}"'-comp"}' 2>/dev/null)
  body_short=$(cat /tmp/resp.body | head -c 120)
  case "$resp" in
    200) result pass "Compensate submit API" "HTTP 200 — $body_short" ;;
    400|404|409) result pass "Compensate API (业务拒绝)" "HTTP $resp — $body_short" ;;
    500)
      # 已知 bug: V13 ck_job_execution_log_type CHECK 不含 COMPENSATION_REJECTED → 拒绝路径 500
      # (compensate 同 jobCode+bizDate 已 running 时 audit log 写不进去) — schema 漏需独立 V8x 修
      if echo "$body_short" | grep -q "SYSTEM_ERROR"; then
        result fail "Compensate API (V13 audit log CHECK 漏 COMPENSATION_REJECTED)" "HTTP 500 — 已知 schema bug"
      else
        result fail "Compensate API" "HTTP 500 — $body_short"
      fi
      ;;
    *) result fail "Compensate API" "HTTP $resp — $body_short" ;;
  esac

  # 9.4 Console-api auth gate
  resp=$(curl -sS -o /tmp/resp.body -w "%{http_code}" \
    "http://localhost:${CONSOLE_PORT}/api/console/queries/job-definitions?tenantId=ta&pageNo=1&pageSize=5" 2>/dev/null)
  case "$resp" in
    401) result pass "Console-api 鉴权拦截" "HTTP 401(无认证 token,gate 工作)" ;;
    200) result pass "Console-api 可达" "HTTP 200(bypass-mode=true)" ;;
    *) result fail "Console-api 可达性" "HTTP $resp body=$(cat /tmp/resp.body | head -c 60)" ;;
  esac

  # 9.5 Trigger 真触发 (CRON 每分钟)
  # 思路: INSERT 一个 enabled CRON job_def → TriggerReconciler ≤30s 接管 →
  #       wheel fire → trigger_request(SCHEDULED) 自增。polling 最多 120s
  # 上限 = 30s reconciler + 60s 下个 cron tick + 缓冲 ≈ 90-120s
  PROBE_FXR_JOB_CODE="${PROBE_TAG}-fxr"
  PROBE_FXR_TENANT="default-tenant"
  fxr_def_id=$(psql_q "INSERT INTO batch.job_definition (
      tenant_id, job_code, job_name, job_type, biz_type,
      schedule_type, schedule_expr, timezone, trigger_mode,
      enabled, created_by
    ) VALUES (
      '$PROBE_FXR_TENANT', '$PROBE_FXR_JOB_CODE', 'seedval cron probe', 'GENERAL', 'TEST',
      'CRON', '0 * * * * ?', 'Asia/Shanghai', 'SCHEDULED',
      true, 'seedval'
    ) ON CONFLICT (tenant_id, job_code) DO UPDATE SET enabled=true RETURNING id")
  if [[ -z "$fxr_def_id" ]]; then
      result fail "Trigger CRON 真触发" "INSERT job_definition 失败,无法验证"
  else
    fxr_baseline=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE tenant_id='$PROBE_FXR_TENANT' AND job_code='$PROBE_FXR_JOB_CODE' AND trigger_type='SCHEDULED'")
    fxr_start=$(date +%s)
    # WheelTriggerReconciler scanIntervalMillis=60000 + 一个 fire 周期 15s + 缓冲 ≈ 90-120s
    fxr_deadline=$(( fxr_start + 120 ))
    fxr_fired=0
    while [[ $(date +%s) -lt $fxr_deadline ]]; do
      fxr_fired=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE tenant_id='$PROBE_FXR_TENANT' AND job_code='$PROBE_FXR_JOB_CODE' AND trigger_type='SCHEDULED'")
      if [[ "$fxr_fired" -gt "$fxr_baseline" ]]; then break; fi
      sleep 5
    done
    fxr_elapsed=$(( $(date +%s) - fxr_start ))
    if [[ "$fxr_fired" -gt "$fxr_baseline" ]]; then
      result pass "Trigger CRON 真触发" "PROBE cron='0 * * * * ?', ${fxr_elapsed}s 内观察到 $fxr_fired 条 SCHEDULED trigger_request (baseline=$fxr_baseline)"
    else
      result fail "Trigger CRON 真触发" "120s 内 trigger_request 无 SCHEDULED 新增 (baseline=$fxr_baseline), 检查 reconciler 30s + cron 周期 60s"
    fi
    # 立即 disable + 显式删 job_definition (避免 PROBE CRON 持续 fire 污染后续 run)
    # do_cleanup 兜底也会清, 这里 explicit 减少 fire 间隔
    psql_q "UPDATE batch.job_definition SET enabled=false WHERE id=$fxr_def_id" >/dev/null
    psql_q "DELETE FROM batch.trigger_request WHERE tenant_id='$PROBE_FXR_TENANT' AND job_code='$PROBE_FXR_JOB_CODE'" >/dev/null
    psql_q "DELETE FROM batch.job_definition WHERE id=$fxr_def_id" >/dev/null
  fi
fi

# ---------- 11. 清理探针(POST cleanup, 全 seedval-* sweep) ----------
section "11. 探针清理(post + EXIT trap 双保险)"

before_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN' OR job_code LIKE '$SWEEP_PATTERN'")
do_cleanup "$SWEEP_PATTERN"
sleep 8
do_cleanup "$SWEEP_PATTERN"
after_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN' OR job_code LIKE '$SWEEP_PATTERN'")
job_def_left=$(psql_q "SELECT count(*) FROM batch.job_definition WHERE job_code LIKE '$SWEEP_PATTERN'")
runtime_left=$(psql_q "SELECT count(*) FROM batch.trigger_runtime_state WHERE job_definition_id IN (SELECT id FROM batch.job_definition WHERE job_code LIKE '$SWEEP_PATTERN')")
# PASS 标准: PROBE job_definition + trigger_runtime_state 必须 0 (源头已断, 不会新增 fire)
# trigger_request 残留容忍 ≤ 15 (CRON PROBE wheel 内存里 stale marker, by-design 自然过期 60-120s,
# 下次 PRE_CLEANUP 启动时会自动清). 详见 WheelTriggerReconciler 注释
if [[ "$job_def_left" == "0" && "$runtime_left" == "0" && "$after_cnt" -le 15 ]]; then
  result pass "探针清理" "源头已断: job_definition=0 + trigger_runtime_state=0; trigger_request 残留 $after_cnt 行 (wheel stale marker, 下轮 PRE_CLEANUP 自清)"
else
  result fail "探针清理" "job_definition=$job_def_left runtime=$runtime_left trigger_request=$after_cnt — 源头未断或异常残留"
fi

# ---------- 汇总 ----------
echo ""
echo "================================================================"
echo "  汇总: ${GREEN}$PASS PASS${NC} / ${RED}$FAIL FAIL${NC} / ${YELLOW}$SKIP SKIP${NC}"
echo "================================================================"
for line in "${RESULTS[@]}"; do
  echo -e "  $line"
done
echo ""

if [[ $FAIL -gt 0 ]]; then
  exit 1
fi
exit 0
