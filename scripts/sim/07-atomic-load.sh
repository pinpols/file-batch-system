#!/usr/bin/env bash
if (( BASH_VERSINFO[0] < 4 )); then
  echo "❌ 需 bash 4+,macOS 默认 3.2 不支持新一代关联数组语法。请 brew install bash 后用 /usr/local/bin/bash 跑。" >&2
  exit 1
fi
# =========================================================
# 07-atomic-load.sh:触发 ADR-029 专用 atomic worker 的 4 类原子任务(shell/sql/stored-proc/http)
#
# 纯 curl 循环,不依赖 k6。模拟混合原子任务负载,验证 atomic worker 在 sim 环境下端到端可用。
#
# 前置:
#   - platform_seed.sql 的 atomic_*_demo job 已 seed(job_type='ATOMIC',默认 tenant=default-tenant)
#   - batch-worker-atomic 进程在跑(start-all.sh 已含 worker-atomic)
#   - 该 worker 上对应执行器已 enable(默认全关:batch.worker.executors.<type>.enabled=true)
#     stored-proc 还需目标过程存在 + schema/白名单放行;http 需出口域名白名单放行
#
# 用法:
#   bash scripts/sim/07-atomic-load.sh                 # 默认 1 轮 × 3 个稳定本地 job
#   ROUNDS=20 bash scripts/sim/07-atomic-load.sh       # 20 轮
#   ATOMIC_TENANT=default-tenant bash scripts/sim/07-atomic-load.sh
#   ONLY=atomic_sql_demo bash scripts/sim/07-atomic-load.sh   # 只触某一个
# =========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SIM_STAGE_NAME="atomic-load"
# shellcheck source=env-common.sh
source "$ROOT/scripts/sim/env-common.sh"

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 1; }

ROUNDS="${ROUNDS:-1}"
ATOMIC_TENANT="${ATOMIC_TENANT:-$BATCH_DEFAULT_TENANT_ID}"
ONLY="${ONLY:-}"

# seed 里的原子任务 demo job(default_params 自带执行器协议,触发无需带 params)。
# atomic_http_demo 指向 example.internal,本地不保证可达;HTTP 成功/超时/取消由 21-atomic-stage5c 覆盖。
ATOMIC_JOBS=(atomic_shell_demo atomic_sql_demo atomic_stored_proc_demo)
[[ -n "$ONLY" ]] && ATOMIC_JOBS=("$ONLY")

total=0; succ=0
REQUEST_IDS=()
# biz_date_for_round() 已抽到 scripts/sim/env-common.sh(共享 helper),此处直接调用。

echo "==> 原子任务负载:${ROUNDS} 轮 × ${#ATOMIC_JOBS[@]} job(tenant=${ATOMIC_TENANT}, startBizDate=${BIZ_DATE})"
START=$(date +%s)

for round in $(seq 1 "$ROUNDS"); do
  round_biz_date="$(biz_date_for_round "$round")"
  for job in "${ATOMIC_JOBS[@]}"; do
    total=$((total + 1))
    req_id="sim-atomic-${round}-${job}-$(date +%s%N | tail -c 8)"
    REQUEST_IDS+=("$req_id")
    resp=$(curl -sf --max-time 30 --connect-timeout 5 -X POST \
      -H "Content-Type: application/json" \
      -H "X-Internal-Secret: $INTERNAL_SECRET" \
      -H "Idempotency-Key: $req_id" \
      -H "X-Tenant-Id: $ATOMIC_TENANT" \
      -H "X-Request-Id: $req_id" \
      "$TRIGGER_BASE/api/triggers/launch" \
      -d "{\"tenantId\":\"$ATOMIC_TENANT\",\"jobCode\":\"$job\",\"triggerType\":\"API\",\"bizDate\":\"$round_biz_date\",\"requestId\":\"$req_id\"}" 2>&1)
    if echo "$resp" | grep -qE '"code"\s*:\s*"(SUCCESS|OK)"'; then
      succ=$((succ + 1))
      printf "."
    else
      printf "x"
    fi
  done
done

echo
ELAPSED=$(( $(date +%s) - START ))
echo "==> 完成:触发 ${succ}/${total} 成功,用时 ${ELAPSED}s"
[[ "$succ" -eq "$total" ]] || { echo "!! 有触发失败,检查 atomic worker 是否在跑 + 执行器是否 enable"; exit 1; }

in_requests="$(printf "'%s'," "${REQUEST_IDS[@]}")"
in_requests="${in_requests%,}"
deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  non_terminal="$(docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc \
    "select count(*) from batch.trigger_request tr left join batch.job_instance i on i.id=tr.related_job_instance_id where tr.tenant_id='${ATOMIC_TENANT}' and tr.request_id in (${in_requests}) and coalesce(i.instance_status,'') not in ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED')" \
    | tr -d '[:space:]')"
  [[ "$non_terminal" == "0" ]] && break
  sleep 2
done

echo "==> atomic 终态汇总"
docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -P pager=off -c \
  "select i.job_code, i.instance_status, t.task_status, t.error_code, left(coalesce(t.error_message,''),120) as error_message from batch.trigger_request tr left join batch.job_instance i on i.id=tr.related_job_instance_id left join batch.job_task t on t.job_instance_id=i.id where tr.tenant_id='${ATOMIC_TENANT}' and tr.request_id in (${in_requests}) order by tr.created_at, i.job_code"

bad_count="$(docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" -tAc \
  "select count(*) from batch.trigger_request tr left join batch.job_instance i on i.id=tr.related_job_instance_id left join batch.job_task t on t.job_instance_id=i.id where tr.tenant_id='${ATOMIC_TENANT}' and tr.request_id in (${in_requests}) and (i.instance_status <> 'SUCCESS' or coalesce(t.task_status,'') <> 'SUCCESS')" \
  | tr -d '[:space:]')"
[[ "$bad_count" == "0" ]] || { echo "!! atomic 终态存在失败/未完成: bad_count=$bad_count"; exit 1; }
echo "==> atomic 执行终态 PASS"
