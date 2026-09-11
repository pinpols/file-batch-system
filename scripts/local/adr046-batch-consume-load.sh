#!/usr/bin/env bash
# =========================================================
# adr046-batch-consume-load.sh
#
# ADR-046 Phase 2 切片 2.3d —— worker 消费端攒批的「全栈负载验收」。
#
# 命题:开 batch.worker.batch-claim.enabled=true 后,高 fan-out 作业的 CLAIM
#       控制面往返从 O(N)(每 partition 一次 claim)降到 ⌈N/K⌉(每 K 个一次
#       claim-batch)。本脚本对真实全栈(orchestrator + workers,flag 开)触发
#       一个高 fan-out 作业,跑到终态后比对 orchestrator 暴露的
#       batch_task_batch_claim_size 指标(count = claim-batch 调用次数,
#       sum = 实际认领 partition 数),给出 PASS/FAIL。
#
# 关系:确定性的「⌈N/K⌉ HTTP 往返」已由单测 HttpTaskExecutionClientTest
#       #claimBatchReducesClaimRoundTripsToCeilNOverK 固化(进 CI,不需独占栈)。
#       本脚本是「真栈端到端」的补充验收,需要一个**独占的本地全栈窗口**
#       (workers 必须以 flag 开启动),不要在别人正在跑的共享栈上跑。
#
# 前置:
#   1. 全栈已起,且 5 个 worker 均以 batch.worker.batch-claim.enabled=true 启动
#      (start-all.sh 前 export BATCH_WORKER_BATCH_CLAIM_ENABLED=true)。
#   2. 高 fan-out fixture 已 seed(默认 TA_PROCESS_STAGE4_EMPTY_SUCCESS,
#      ~185 partition;用 JOB_CODE / TENANT_ID / BIZ_DATE 覆盖换别的)。
#
# 用法:
#   ./scripts/local/adr046-batch-consume-load.sh
#   JOB_CODE=my_job TENANT_ID=default-tenant BIZ_DATE=2026-06-04 \
#     EXPECT_MIN_PARTITIONS=100 ./scripts/local/adr046-batch-consume-load.sh
#
# 环境变量:
#   TRIGGER_PORT / ORCH_PORT          服务端口(默认 18081 / 18082)
#   INTERNAL_SECRET                   trigger /api/* 共享密钥
#   PG_CONTAINER / PG_USER / PG_DB    PG 容器(默认 batch-postgres-primary / batch_user / batch_platform)
#   JOB_CODE / TENANT_ID / BIZ_DATE   要触发的高 fan-out 作业
#   EXPECT_MIN_PARTITIONS             低于此 partition 数视为 fan-out 不够、判 SKIP(默认 50)
#   AWAIT_TIMEOUT                     等终态秒数(默认 180)
#
# 退出码:0 = PASS;1 = FAIL;2 = SKIP(前置不满足)。
# =========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
batch_load_default_env

TRIGGER_PORT="${TRIGGER_PORT:-${TRIGGER_BASE_URL##*:}}"
ORCH_PORT="${ORCH_PORT:-${ORCHESTRATOR_BASE_URL##*:}}"
INTERNAL_SECRET="${INTERNAL_SECRET:-$BATCH_INTERNAL_SECRET}"
PG_CONTAINER="${PG_CONTAINER:-batch-postgres-primary}"
PG_USER="${PG_USER:-$PGUSER}"
PG_DB="${PG_DB:-$PLATFORM_DB}"
JOB_CODE="${JOB_CODE:-TA_PROCESS_STAGE4_EMPTY_SUCCESS}"
TENANT_ID="${TENANT_ID:-$BATCH_DEFAULT_TENANT_ID}"
BIZ_DATE="${BIZ_DATE:-2026-06-04}"
EXPECT_MIN_PARTITIONS="${EXPECT_MIN_PARTITIONS:-50}"
AWAIT_TIMEOUT="${AWAIT_TIMEOUT:-180}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'

say()  { echo -e "${BLUE}==>${NC} $*"; }
pass() { echo -e "${GREEN}🟢 PASS${NC} $*"; }
fail() { echo -e "${RED}🔴 FAIL${NC} $*"; }
skip() { echo -e "${YELLOW}🟡 SKIP${NC} $*"; exit 2; }

psql_q() {
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

# 抓 orchestrator 暴露的 micrometer 指标(prometheus 文本格式)。
# DistributionSummary batch.task.batch_claim.size → batch_task_batch_claim_size_count / _sum。
scrape() {
  local metric="$1"
  curl -sS --max-time 10 "http://localhost:${ORCH_PORT}/actuator/prometheus" 2>/dev/null \
    | awk -v m="$metric" '$1==m {print $2; found=1} END{if(!found)print 0}' | head -1
}

# ---------- 0. 前置探活 ----------
say "前置探活: orchestrator :$ORCH_PORT / PG $PG_CONTAINER"
curl -sS --max-time 5 "http://localhost:${ORCH_PORT}/actuator/health" >/dev/null 2>&1 \
  || skip "orchestrator :$ORCH_PORT 不可达 —— 需先起全栈(flag 开)"
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "SELECT 1" >/dev/null 2>&1 \
  || skip "PG $PG_CONTAINER 不可达"

# claim-batch 指标存在性 = orchestrator 侧 batch 路径可观测(2.4)。
if [[ "$(scrape batch_task_batch_claim_size_count)" == "0" ]] \
   && ! curl -sS --max-time 5 "http://localhost:${ORCH_PORT}/actuator/prometheus" 2>/dev/null \
        | grep -q batch_task_batch_claim_size; then
  say "提示: 当前 batch_claim 指标为空 —— 若 worker 未以 flag 开启动,本次只会走单条路径"
fi

# ---------- 1. 指标基线快照 ----------
BEFORE_CALLS=$(scrape batch_task_batch_claim_size_count)
BEFORE_PARTS=$(scrape batch_task_batch_claim_size_sum)
say "基线: claim-batch calls=$BEFORE_CALLS, partitions(sum)=$BEFORE_PARTS"

# ---------- 2. 触发高 fan-out 作业 ----------
REQUEST_ID="adr046-2.3d-$(date +%s)"
say "触发 jobCode=$JOB_CODE tenant=$TENANT_ID bizDate=$BIZ_DATE (reqId=$REQUEST_ID)"
http_code=$(curl -sS --max-time 30 --connect-timeout 5 -o /tmp/adr046-2.3d.resp -w "%{http_code}" \
  -X POST "http://localhost:${TRIGGER_PORT}/api/triggers/launch" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Secret: $INTERNAL_SECRET" \
  -H "Idempotency-Key: $REQUEST_ID" \
  -d "{\"tenantId\":\"$TENANT_ID\",\"jobCode\":\"$JOB_CODE\",\"bizDate\":\"$BIZ_DATE\",\"triggerType\":\"API\"}" 2>/dev/null)
if [[ "$http_code" != "200" ]]; then
  fail "launch 返回 HTTP $http_code: $(head -c 200 /tmp/adr046-2.3d.resp)"
  exit 1
fi

# 拿到 job_instance_id
JI_ID=""; elapsed=0
while [[ $elapsed -lt 30 ]]; do
  JI_ID=$(psql_q "SELECT related_job_instance_id FROM batch.trigger_request WHERE tenant_id='$TENANT_ID' AND request_id='$REQUEST_ID' AND request_status='LAUNCHED'")
  [[ -n "$JI_ID" ]] && break
  sleep 1; elapsed=$((elapsed+1))
done
[[ -z "$JI_ID" ]] && { fail "30s 内未 LAUNCHED(local profile lazy 会卡 relay,需 docker 或 lazy=false)"; exit 1; }
say "job_instance_id=$JI_ID"

# ---------- 3. 等终态 ----------
say "等 partition 终态(超时 ${AWAIT_TIMEOUT}s)..."
elapsed=0; total=0; succ=0; running=0
while [[ $elapsed -lt $AWAIT_TIMEOUT ]]; do
  total=$(psql_q "SELECT count(*) FROM batch.job_partition WHERE job_instance_id=$JI_ID")
  running=$(psql_q "SELECT count(*) FROM batch.job_partition WHERE job_instance_id=$JI_ID AND partition_status NOT IN ('SUCCESS','FAILED','SKIPPED','CANCELLED')")
  [[ "$total" -gt 0 && "$running" == "0" ]] && break
  sleep 2; elapsed=$((elapsed+2))
done
succ=$(psql_q "SELECT count(*) FROM batch.job_partition WHERE job_instance_id=$JI_ID AND partition_status='SUCCESS'")
say "partitions: total=$total success=$succ still-running=$running (${elapsed}s)"

if [[ "${total:-0}" -lt "$EXPECT_MIN_PARTITIONS" ]]; then
  skip "fan-out 不足: total=$total < EXPECT_MIN_PARTITIONS=$EXPECT_MIN_PARTITIONS(换高 fan-out fixture)"
fi
if [[ "$running" != "0" ]]; then
  fail "超时仍有 $running 个 partition 未终态"
  exit 1
fi

# ---------- 4. 指标增量 + 验收判定 ----------
AFTER_CALLS=$(scrape batch_task_batch_claim_size_count)
AFTER_PARTS=$(scrape batch_task_batch_claim_size_sum)
# micrometer count/sum 是浮点,用 awk 算增量
D_CALLS=$(awk -v a="$AFTER_CALLS" -v b="$BEFORE_CALLS" 'BEGIN{printf "%.0f", a-b}')
D_PARTS=$(awk -v a="$AFTER_PARTS" -v b="$BEFORE_PARTS" 'BEGIN{printf "%.0f", a-b}')
say "本次 claim-batch 调用增量: calls=$D_CALLS, partitions=$D_PARTS"

echo
echo "================= 2.3d 验收 ================="
echo "  job_instance:        $JI_ID ($JOB_CODE)"
echo "  partitions SUCCESS:  $succ / $total"
echo "  claim-batch 调用数:   $D_CALLS"
echo "  认领 partition 数:    $D_PARTS"

if [[ "$D_CALLS" -le 0 ]]; then
  fail "claim-batch 调用增量为 0 —— worker 未走 batch 路径(flag 没开?用单条路径跑的)"
  exit 1
fi
EFF_K=$(awk -v p="$D_PARTS" -v c="$D_CALLS" 'BEGIN{if(c>0)printf "%.1f", p/c; else print 0}')
REDUCT=$(awk -v p="$D_PARTS" -v c="$D_CALLS" 'BEGIN{if(p>0)printf "%.1f", (1-c/p)*100; else print 0}')
echo "  有效 K (parts/call):  $EFF_K"
echo "  往返削减:             ${REDUCT}%  (单条路径需 $D_PARTS 次 CLAIM)"
echo "============================================"

# PASS 条件:① 全部 partition SUCCESS;② 攒批确实生效(calls < partitions,即 K>1 有削减)
if [[ "$succ" == "$total" ]] && [[ "$D_CALLS" -lt "$D_PARTS" ]]; then
  pass "高 fan-out 全 SUCCESS 且 CLAIM 往返 $D_PARTS→$D_CALLS(削减 ${REDUCT}%),O(N)→⌈N/K⌉ 成立"
  exit 0
fi
fail "未达标: success=$succ/$total, calls=$D_CALLS, partitions=$D_PARTS"
exit 1
