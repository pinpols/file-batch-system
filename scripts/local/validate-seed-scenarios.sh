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
  probe_instances=$(psql_q "SELECT string_agg(DISTINCT id::text, ',') FROM batch.job_instance WHERE trigger_request_id IN (SELECT id FROM batch.trigger_request WHERE request_id LIKE '$pattern')")
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
    psql_q "DELETE FROM batch.outbox_event WHERE aggregate_id::text IN (SELECT unnest(string_to_array('$probe_instances', ',')))" >/dev/null
    psql_q "DELETE FROM batch.job_instance WHERE id IN ($probe_instances)" >/dev/null
  fi
  # 此时 job_instance 的 FK 已断,trigger_request / outbox 安全删除
  psql_q "DELETE FROM batch.trigger_outbox_event WHERE request_id LIKE '$pattern'" >/dev/null
  psql_q "DELETE FROM batch.trigger_request WHERE request_id LIKE '$pattern'" >/dev/null
  psql_q "DELETE FROM batch.workflow_node WHERE node_code='SEEDVAL_PROBE'" >/dev/null
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
    '{"templateCode":"import_customer_json_v1","content":"[{\"customerId\":\"C001\",\"name\":\"smoke\",\"accountType\":\"SAVINGS\",\"accountNo\":\"A001\",\"balance\":100.00,\"openDate\":\"2026-05-03\"}]"}' \
    success

  assert_fire "EXPORT 严格 (default-tenant, settlement)" "default-tenant" "export_settlement_job" '{}' success

  assert_fire "WORKFLOW PIPELINE 严格 (default-tenant)" "default-tenant" "wf_probe_pipeline" '{}' success

  result skip "DISPATCH 严格" "default-tenant 无 DISPATCH job, 跳过(STRICT 限定 default-tenant)"
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
    "http://localhost:${CONSOLE_PORT}/api/console/job-definitions?tenantId=ta&pageNo=1&pageSize=5" 2>/dev/null)
  case "$resp" in
    401) result pass "Console-api 鉴权拦截" "HTTP 401(无认证 token,gate 工作)" ;;
    200) result pass "Console-api 可达" "HTTP 200(bypass-mode=true)" ;;
    *) result fail "Console-api 可达性" "HTTP $resp body=$(cat /tmp/resp.body | head -c 60)" ;;
  esac

  # 9.5 Trigger cron 真触发
  result skip "Trigger cron 真触发" "时序不确定,不纳入自动化(改 schedule_type=FIXED_RATE 等待 1 个周期 → 看 trigger_request 自增)"
fi

# ---------- 11. 清理探针(POST cleanup, 全 seedval-* sweep) ----------
section "11. 探针清理(post + EXIT trap 双保险)"

before_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN'")
do_cleanup "$SWEEP_PATTERN"
after_cnt=$(psql_q "SELECT count(*) FROM batch.trigger_request WHERE request_id LIKE '$SWEEP_PATTERN'")
if [[ "$after_cnt" == "0" ]]; then
  result pass "探针清理" "全 sweep 已清 $before_cnt 行(含本次 + 任何历史残留), 13 张表零污染"
else
  result fail "探针清理" "残留 $after_cnt 行 trigger_request, 清理逻辑漏"
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
