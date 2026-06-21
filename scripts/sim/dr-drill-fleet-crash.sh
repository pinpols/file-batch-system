#!/usr/bin/env bash
# ============================================================================
# DR 演练:worker 全 worker 组崩溃 → lease/task 超时回收 + 重投 → 重启 → 断言「精确一次」
# ----------------------------------------------------------------------------
# 上线就绪检查表 docs/runbook/go-live-readiness.md §2-C 的可执行件。
#
# 验证的承重墙:整组 worker 在飞时全崩,平台靠 lease/task 超时回收 + Kafka 重投
# 让任务在另一副本重跑;终态必须「精确一次」——不得出现重复终态、重复 outbox、
# job_instance 复活或长期停滞。逻辑层已有 IT 覆盖(ConcurrentTaskFinish / Outbox*
# / WorkerHeartbeatTimeoutScheduler);本演练在生产同构 staging 上端到端实证。
#
# 前置:sim/staging 栈已起(02-start-sim.sh),已有载荷在飞(05-load.sh 等)。
# 用法:
#   PG_CONTAINER=sim-postgres POSTGRES_USER=batch PG_PLATFORM_DB=batch_platform \
#   WORKER_CONTAINERS="worker-import worker-export worker-process" \
#   SETTLE_TIMEOUT_S=180 ./scripts/sim/dr-drill-fleet-crash.sh
# 退出码:0=精确一次通过;1=断言失败(发现重复/复活/长期停滞);2=前置/环境错误。
# ============================================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:?需设置 PG_CONTAINER(平台 PG 容器名)}"
POSTGRES_USER="${POSTGRES_USER:-batch}"
PG_PLATFORM_DB="${PG_PLATFORM_DB:-batch_platform}"
WORKER_CONTAINERS="${WORKER_CONTAINERS:?需设置 WORKER_CONTAINERS(空格分隔的 worker 容器名)}"
# 回收 + 重投 + 重跑的最大等待秒数。需 ≥ lease/task 超时阈值 + 一轮重跑。
SETTLE_TIMEOUT_S="${SETTLE_TIMEOUT_S:-180}"
POLL_S="${POLL_S:-5}"

command -v docker >/dev/null 2>&1 || { echo "❌ 需要 docker" >&2; exit 2; }

psql_platform() {
  docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PG_PLATFORM_DB" -tAc "$1"
}

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ---- 1) 基线:记录当前在飞(RUNNING/CLAIMED)的任务集合 -------------------
log "采集基线:在飞任务快照"
INFLIGHT_BEFORE="$(psql_platform \
  "select count(*) from batch.job_task where task_status in ('RUNNING','READY');")"
log "在飞 job_task=$INFLIGHT_BEFORE"
if [ "${INFLIGHT_BEFORE:-0}" -lt 1 ]; then
  echo "❌ 没有在飞任务可崩;先起载荷(05-load.sh)再演练" >&2
  exit 2
fi

# ---- 2) 全 worker 组崩溃:kill 所有 worker 容器(SIGKILL,模拟硬崩非优雅停)---------
# 自动清理:本演练唯一的残留是「被 kill 的 worker」。装 EXIT trap,确保无论脚本
# 正常退出、断言失败 exit、还是 Ctrl-C/出错中断,都把被本脚本 kill 的 worker 拉回
# running——不留 killed 容器残留。(基础设施栈 PG/Kafka/MinIO 不是本脚本起的,不动。)
KILLED=""
restart_killed_workers() {
  [ -z "$KILLED" ] && return 0
  log "清理:恢复被本演练 kill 的 worker($KILLED)"
  for c in $KILLED; do
    docker start "$c" >/dev/null 2>&1 || echo "⚠️ 清理时无法重启 $c(请手工 docker start $c)" >&2
  done
}
trap restart_killed_workers EXIT

log "全 worker 组崩溃:kill $WORKER_CONTAINERS"
for c in $WORKER_CONTAINERS; do
  if docker kill "$c" >/dev/null 2>&1; then
    KILLED="$KILLED $c"
  else
    log "⚠️ kill $c 失败(可能已停)"
  fi
done

# ---- 3) 等待平台回收(lease/heartbeat/task 超时)+ Kafka 重投 -------------
log "等待 lease/task 超时回收(WorkerHeartbeatTimeoutScheduler / TaskTimeoutEnforcer)"
sleep "$POLL_S"

# ---- 4) 重启整队 worker,接重投的任务 ------------------------------------
log "重启 worker:$WORKER_CONTAINERS"
for c in $WORKER_CONTAINERS; do
  docker start "$c" >/dev/null 2>&1 || { echo "❌ 无法重启 $c" >&2; exit 2; }
done
KILLED=""  # 已主动拉回,后续 EXIT trap 不再重复 start(仅在中途中断时兜底)

# ---- 5) 轮询直到沉降(无 RUNNING/READY 残留)或超时 ----------------------
log "轮询沉降(≤ ${SETTLE_TIMEOUT_S}s)"
deadline=$(( $(date +%s) + SETTLE_TIMEOUT_S ))
while :; do
  pending="$(psql_platform \
    "select count(*) from batch.job_task where task_status in ('RUNNING','READY');")"
  [ "${pending:-1}" -eq 0 ] && { log "已沉降"; break; }
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "❌ 超时未沉降:仍有 ${pending} 个 job_task 卡在 RUNNING/READY(疑似长期停滞/未重投)" >&2
    exit 1
  fi
  sleep "$POLL_S"
done

# ---- 6) 精确一次断言 ------------------------------------------------------
fail=0

dup_instances="$(psql_platform "
  select coalesce(string_agg(tenant_id||'/'||dedup_key||':'||c, ', '), '')
  from (select tenant_id, dedup_key, count(*) c
        from batch.job_instance
        group by tenant_id, dedup_key, run_attempt having count(*) > 1) d;")"
if [ -n "${dup_instances:-}" ]; then
  echo "❌ 重复 job_instance(同 tenant/dedup_key/run_attempt):$dup_instances" >&2; fail=1
else log "✅ 无重复 job_instance(UNIQUE(tenant_id,dedup_key,run_attempt) 承重)"; fi

dup_outbox="$(psql_platform "
  select coalesce(string_agg(tenant_id||'/'||event_key||':'||c, ', '), '')
  from (select tenant_id, event_key, count(*) c
        from batch.outbox_event
        group by tenant_id, event_key having count(*) > 1) d;")"
if [ -n "${dup_outbox:-}" ]; then
  echo "❌ 重复 outbox_event(同 tenant/event_key):$dup_outbox" >&2; fail=1
else log "✅ 无重复 outbox_event(UNIQUE(tenant_id,event_key) 承重)"; fi

stuck="$(psql_platform \
  "select count(*) from batch.job_task where task_status in ('RUNNING','READY');")"
if [ "${stuck:-0}" -ne 0 ]; then
  echo "❌ 仍有 ${stuck} 个 job_task 未达终态(复活/长期停滞)" >&2; fail=1
else log "✅ 所有 job_task 已达终态"; fi

# 终态分布(给运维看,非门禁)
log "终态分布:"
psql_platform "select task_status, count(*) from batch.job_task
               group by task_status order by 2 desc;" | sed 's/|/ = /'

if [ "$fail" -ne 0 ]; then
  echo "❌ DR 全 worker 组崩溃演练:精确一次断言失败" >&2
  exit 1
fi
log "✅ DR 全 worker 组崩溃演练通过:全 worker 组崩溃 → 回收重投 → 重跑,终态精确一次"
