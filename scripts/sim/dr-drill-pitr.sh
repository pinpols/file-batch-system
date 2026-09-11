#!/usr/bin/env bash
# ============================================================================
# DR 演练:PITR(时间点恢复)—— 验证 RPO(已提交数据不丢)+ RTO(恢复耗时)
# ----------------------------------------------------------------------------
# 上线就绪检查表 docs/runbook/go-live-readiness.md §4 的可执行件。
#
# 备份工具无关:演练逻辑(基线→选恢复点 T0→恢复→断言)通用,真正的"恢复"动作
# 做成可插拔钩子 RESTORE_CMD,你接 pgBackRest / WAL-G / 云托管 PITR(RDS/Aurora
# 快照恢复)均可。脚本只负责"测量 + 断言",不假设任何具体备份方案。
#
# !!! 仅在 DR / staging 演练环境跑 —— 恢复动作会改写/重建数据库,严禁对生产跑。
#
# 流程:
#   T0 := 现在(恢复目标时间点)
#   S0 := T0 前已提交的 job_instance 集合(必须在恢复后全部存活 → RPO)
#   等 S1(T0 之后的新数据)产生,恢复到 T0 后它应不存在(证明恢复点精确)
#   触发 RESTORE_CMD(target-time=T0),计时 → RTO
#   断言:S0 全在(无丢失,RPO 达标)、RTO ≤ 预算、无重复/悬挂
#
# 用法(RESTORE_CMD 内可引用已导出的 RESTORE_TARGET_TIME,ISO8601):
#   PG_CONTAINER=sim-postgres POSTGRES_USER=batch PG_PLATFORM_DB=batch_platform \
#   RTO_BUDGET_S=7200 \
#   RESTORE_CMD='pgbackrest --stanza=batch --type=time --target="$RESTORE_TARGET_TIME" restore' \
#   ./scripts/sim/dr-drill-pitr.sh
#
# 退出码:0=RPO/RTO 达标;1=断言失败(丢数据/超时/不一致);2=前置/环境错误。
# ============================================================================
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:?需设置 PG_CONTAINER}"
POSTGRES_USER="${POSTGRES_USER:-batch}"
PG_PLATFORM_DB="${PG_PLATFORM_DB:-batch_platform}"
RESTORE_CMD="${RESTORE_CMD:?需设置 RESTORE_CMD(你的备份工具恢复命令;可用 RESTORE_TARGET_TIME)}"
RTO_BUDGET_S="${RTO_BUDGET_S:-7200}"
READY_TIMEOUT_S="${READY_TIMEOUT_S:-1800}"
S1_DWELL_S="${S1_DWELL_S:-15}"
POLL_S="${POLL_S:-5}"

command -v docker >/dev/null 2>&1 || { echo "需要 docker" >&2; exit 2; }
log() { echo "[$(date +%H:%M:%S)] $*"; }
psql_platform() {
  docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PG_PLATFORM_DB" -tAc "$1"
}

# ---- 1) 选恢复目标时间点 T0,快照 T0 前已提交集合 S0(count + 指纹)-------
RESTORE_TARGET_TIME="$(psql_platform "select now()::timestamptz;")"
export RESTORE_TARGET_TIME
log "恢复目标时间点 T0 = $RESTORE_TARGET_TIME"

S0_COUNT="$(psql_platform "select count(*) from batch.job_instance where created_at <= '$RESTORE_TARGET_TIME'::timestamptz;")"
S0_FINGERPRINT="$(psql_platform "select coalesce(md5(string_agg(tenant_id||'/'||dedup_key||'/'||run_attempt, ',' order by id)), 'EMPTY') from batch.job_instance where created_at <= '$RESTORE_TARGET_TIME'::timestamptz;")"
log "S0(T0 前已提交 job_instance):count=$S0_COUNT fp=$S0_FINGERPRINT"
if [ "${S0_COUNT:-0}" -lt 1 ]; then
  echo "T0 前没有已提交数据可验;先起载荷(05-load.sh 等)再演练" >&2
  exit 2
fi

# ---- 2) 等 T0 之后新数据 S1 产生(由外部 sim/load 持续运行)--------------
log "等待 T0 之后新增载荷 ${S1_DWELL_S}s(恢复到 T0 后应不存在,证明恢复点精确)"
sleep "$S1_DWELL_S"
S1_AFTER="$(psql_platform "select count(*) from batch.job_instance where created_at > '$RESTORE_TARGET_TIME'::timestamptz;")"
log "S1(T0 之后新增)=$S1_AFTER"

# ---- 3) 触发恢复(计 RTO)------------------------------------------------
log "触发 PITR 恢复 target-time=$RESTORE_TARGET_TIME(执行 RESTORE_CMD)"
restore_start=$(date +%s)
if ! bash -c "$RESTORE_CMD"; then
  echo "RESTORE_CMD 执行失败" >&2; exit 1
fi
log "等待数据库恢复就绪(≤ ${READY_TIMEOUT_S}s)"
ready_deadline=$(( restore_start + READY_TIMEOUT_S ))
until psql_platform "select 1;" >/dev/null 2>&1; do
  [ "$(date +%s)" -ge "$ready_deadline" ] && { echo "恢复后数据库超时未就绪" >&2; exit 1; }
  sleep "$POLL_S"
done
RTO_S=$(( $(date +%s) - restore_start ))
log "恢复就绪,RTO = ${RTO_S}s(预算 ${RTO_BUDGET_S}s)"

# ---- 4) 断言:RPO(S0 不丢)+ RTO(≤ 预算)+ 一致性 ----------------------
fail=0
S0_COUNT_AFTER="$(psql_platform "select count(*) from batch.job_instance where created_at <= '$RESTORE_TARGET_TIME'::timestamptz;")"
S0_FP_AFTER="$(psql_platform "select coalesce(md5(string_agg(tenant_id||'/'||dedup_key||'/'||run_attempt, ',' order by id)), 'EMPTY') from batch.job_instance where created_at <= '$RESTORE_TARGET_TIME'::timestamptz;")"

if [ "$S0_FP_AFTER" = "$S0_FINGERPRINT" ] && [ "${S0_COUNT_AFTER:-0}" -ge "${S0_COUNT:-0}" ]; then
  log "RPO 通过:T0 前已提交数据完整存活(count $S0_COUNT->$S0_COUNT_AFTER,指纹一致)"
else
  echo "RPO 失败:T0 前数据丢失/错位(count $S0_COUNT->$S0_COUNT_AFTER,fp $S0_FINGERPRINT vs $S0_FP_AFTER)" >&2
  fail=1
fi

if [ "$RTO_S" -le "$RTO_BUDGET_S" ]; then
  log "RTO 通过:${RTO_S}s <= 预算 ${RTO_BUDGET_S}s"
else
  echo "RTO 超预算:${RTO_S}s > ${RTO_BUDGET_S}s" >&2; fail=1
fi

dup="$(psql_platform "select count(*) from (select tenant_id, dedup_key, run_attempt from batch.job_instance group by 1,2,3 having count(*)>1) d;")"
if [ "${dup:-0}" -eq 0 ]; then log "恢复后无重复 job_instance";
else echo "恢复后出现重复 job_instance:$dup 组" >&2; fail=1; fi

[ "$fail" -ne 0 ] && { echo "PITR 演练失败" >&2; exit 1; }
log "PITR 演练通过:RPO 不丢已提交数据,RTO ${RTO_S}s <= ${RTO_BUDGET_S}s,恢复后一致"
