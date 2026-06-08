#!/usr/bin/env bash
if (( BASH_VERSINFO[0] < 4 )); then
  echo "❌ 需 bash 4+,macOS 默认 3.2 不支持新一代关联数组语法。请 brew install bash 后用 /usr/local/bin/bash 跑。" >&2
  exit 1
fi
# =========================================================
# 06-verify.sh:对账 4 类 worker 的产物
#
# - IMPORT:biz.* 表行数(ta=customer / tb=transaction / tc=risk_score)
# - EXPORT:MinIO ta/tb/tc outbound prefix 下产物
# - DISPATCH:MockServer received-request 计数(tb/callback / tb/ingest / tc/ingest)
# - WORKFLOW:job_instance / workflow_run 完成数
# =========================================================
set -uo pipefail

PG="${PG_CONTAINER:-batch-postgres-primary}"
MINIO="${MINIO_CONTAINER:-batch-minio}"
BUCKET="${MINIO_BUCKET:-batch-dev}"
MOCK_BASE="${MOCK_BASE:-http://localhost:11080}"

PG_USER="${PG_USER:-batch_user}"
psql_q() { docker exec "$PG" psql -U "$PG_USER" -d "$1" -tAc "$2" 2>/dev/null; }
psql_b() { docker exec "$PG" psql -U "$PG_USER" -d batch_business -tAc "$1" 2>/dev/null; }

GREEN='\033[32m' RED='\033[31m' YELLOW='\033[33m' BLUE='\033[34m' RST='\033[0m'
hdr() { printf "\n${BLUE}── %s ──${RST}\n" "$1"; }
ok()  { printf "  ${GREEN}✓${RST} %-32s %s\n" "$1" "$2"; }
ng()  { printf "  ${RED}✗${RST} %-32s %s\n" "$1" "$2"; }
note(){ printf "  ${YELLOW}·${RST} %-32s %s\n" "$1" "$2"; }

# =========================================================
# PREREQ:报告 0 文件的常见根因(2026-06-03)
#   1) 03-import-tenants.sh 没跑 → job_definition 缺 EXPORT,launch fail 但 05 不
#      区分 launch ack 与 后续 worker 执行结果。
#   2) worker-export 进程没起来(只在仓库本地 Maven 跑,不进 docker compose) →
#      job_instance.step 永远停在 READY,job 60~120 分钟后超时 FAILED。
#   3) ROUNDS / sleep 不够,worker 还在 RUNNING。
# 命中即 echo 引导,不阻断后续打印(继续跑帮助现场观察)。
# =========================================================
hdr "PREREQ(若 EXPORT/DISPATCH 报 0 文件先看这里)"

# (a) EXPORT job_definition 是否齐(说明 03-import-tenants.sh 已跑)
export_defs=$(psql_q batch_platform \
  "select count(*) from batch.job_definition where job_code in
   ('TA_EXPORT_REPORT','TB_EXPORT_STATEMENT','TC_EXPORT_RISK_ALERT')" | tr -dc '0-9')
export_defs="${export_defs:-0}"
if [[ "$export_defs" -ge 3 ]]; then
  ok "EXPORT job_definition" "$export_defs / 3"
else
  ng "EXPORT job_definition" "$export_defs / 3,请先跑 bash scripts/sim/03-import-tenants.sh"
fi

# (b) worker-export 进程是否在听 18084 / 容器是否在
worker_ok=0
if lsof -i :18084 -sTCP:LISTEN >/dev/null 2>&1; then
  ok "worker-export :18084" "LISTEN(本地 mvn 进程)"
  worker_ok=1
elif docker ps --format '{{.Names}}' | grep -q '^batch-worker-export$'; then
  ok "worker-export 容器" "running"
  worker_ok=1
else
  ng "worker-export" "未运行,EXPORT step 会停在 READY → 60+min 后 FAILED;请启动 worker-export"
fi

# (c) 近 10min FAILED 且 step 全停在 READY = 典型无 worker 现场
ready_fail=$(psql_q batch_platform "select count(*) from batch.job_instance i
  where i.job_code like '%EXPORT%' and i.instance_status='FAILED'
    and i.created_at > now() - interval '2 hour'
    and not exists (select 1 from batch.job_step_instance s
      where s.job_instance_id=i.id and s.step_status not in ('READY'))" | tr -dc '0-9')
ready_fail="${ready_fail:-0}"
if [[ "$ready_fail" -gt 0 ]]; then
  ng "EXPORT FAILED + step READY" "$ready_fail 个 — 强烈指示 worker-export 当时未运行"
fi

# (d) 触发后等待:60s 偏短,EXPORT(查询 → 写文件 → upload)在 macOS 上 90~120s 才稳
note "sleep 提示" "触发 05 后建议 sleep 120(EXPORT 写 MinIO,60s 偏紧)"

hdr "IMPORT 产物(biz.* 表行数)"
for entry in "ta:customer_account" "tb:transaction" "tc:risk_score"; do
  tenant="${entry%%:*}"; table="${entry##*:}"
  cnt=$(psql_b "select count(*) from biz.$table where tenant_id='$tenant'" 2>/dev/null | tr -dc '0-9')
  cnt="${cnt:-0}"
  if [[ "$cnt" -gt 0 ]]; then
    ok "biz.${table}[${tenant}]" "$cnt 行"
  else
    note "biz.${table}[${tenant}]" "0 行(任务可能未跑完 / source 缺数据)"
  fi
done

hdr "EXPORT 产物(MinIO $BUCKET 各 tenant outbound)"
for prefix in "outbound/TA_EXPORT_REPORT" "outbound/TB_EXPORT_STATEMENT" "outbound/TC_EXPORT_RISK_ALERT"; do
  cnt=$(docker exec "$MINIO" mc ls --recursive "local/$BUCKET/$prefix" 2>/dev/null \
        | grep -v '/$' \
        | grep -vc '\.keep$' \
        | tr -dc '0-9')
  cnt="${cnt:-0}"
  if [[ "$cnt" -gt 0 ]]; then
    ok "$prefix" "$cnt 文件"
    docker exec "$MINIO" mc ls --recursive "local/$BUCKET/$prefix" 2>/dev/null \
      | grep -v '/$' \
      | grep -v '\.keep$' \
      | head -3 | sed 's/^/      /'
  else
    note "$prefix" "0 文件(EXPORT 可能未跑)"
  fi
done

hdr "DISPATCH 产物(MockServer 收到的请求数)"
for path in "/tb/callback" "/tb/ingest" "/tc/ingest"; do
  cnt=$(curl -sf --max-time 30 --connect-timeout 5 -X PUT "$MOCK_BASE/mockserver/retrieve?type=REQUESTS&format=JSON" \
        -H "Content-Type: application/json" \
        -d "{\"path\":\"$path\"}" 2>/dev/null | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null | tr -dc '0-9')
  cnt="${cnt:-0}"
  if [[ "$cnt" -gt 0 ]]; then
    ok "POST $path" "$cnt 次"
  else
    note "POST $path" "0 次(DISPATCH 可能未跑)"
  fi
done

hdr "WORKFLOW + 全局 job_instance 状态"
psql_q batch_platform "select instance_status, count(*) from batch.job_instance where created_at > now() - interval '10 min' group by instance_status order by instance_status" | head -10 | sed 's/^/    /'

hdr "Outbox 积压检查(健康度)"
backlog=$(psql_q batch_platform "select count(*) from batch.outbox_event where publish_status in ('NEW','FAILED')" 2>/dev/null | tr -dc '0-9')
backlog="${backlog:-0}"
if [[ "$backlog" -lt 10 ]]; then
  ok "outbox backlog" "$backlog(健康)"
else
  ng "outbox backlog" "$backlog ⚠️ 上游有积压"
fi

echo
echo "==> 验收完毕"
