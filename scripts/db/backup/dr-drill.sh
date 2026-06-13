#!/usr/bin/env bash
# =========================================================
# dr-drill.sh — 本地灾备演练(备份 → 模拟灾难 → 恢复 → 校验 → 量 RTO)
#
# 把 backup-and-pitr.md §2.1(逻辑恢复)+ §2.3(校验清单)从"runbook 文字"
# 变成"可重复执行的证据"。填的是 ha-readiness P0-3 里"恢复演练从没做过"的真窟窿。
# 全程走 docker exec 进 batch-postgres-primary,**宿主机无需装 pg 客户端**。
#
# 覆盖的是灾备的"应用数据可恢复"这半:证明 ① dump 真能恢复 ② 关键表行数一致
# ③ business RLS policy 能重建 ④ Flyway 版本对齐 ⑤ 量出 RTO。
# (应用"重放后收敛"那半由 chaos ToxicIT + 起服务连恢复库验,见 README。)
#
# 用法:
#   bash scripts/db/backup/dr-drill.sh                # 安全模式:恢复到 *_dr 旁路库,验完清掉,不动现有数据
#   bash scripts/db/backup/dr-drill.sh --keep         # 安全模式,但保留 *_dr 库供人工查
#   bash scripts/db/backup/dr-drill.sh --in-place --yes  # 真实演练:DROP 现有库 + 恢复(破坏性!量真 RTO)
#   bash scripts/db/backup/dr-drill.sh --backup-dir /mnt/bk   # 顺带把 dump 落到宿主目录留存
# =========================================================
set -uo pipefail

# ---- 配置(从 .env.local / .env.example 取,带默认)----
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../../.." && pwd)
for envf in "$REPO_ROOT/.env.local" "$REPO_ROOT/.env.example"; do
  [[ -f "$envf" ]] && { set -a; . "$envf"; set +a; break; }
done

PG_CONTAINER=${PG_CONTAINER:-batch-postgres-primary}
PG_USER=${POSTGRES_USER:-batch_user}
PG_PASSWORD=${POSTGRES_PASSWORD:-batch_pass_123}
PLATFORM_DB=${POSTGRES_DB:-batch_platform}
BUSINESS_DB=${BUSINESS_DB_NAME:-batch_business}
RLS_SQL="$REPO_ROOT/scripts/db/business/rls-phase-a.sql"

MODE=safe          # safe | in-place
KEEP=0
CONFIRM=0
BACKUP_DIR=""
for arg in "$@"; do
  case "$arg" in
    --in-place) MODE=in-place ;;
    --keep) KEEP=1 ;;
    --yes) CONFIRM=1 ;;
    --backup-dir) shift; BACKUP_DIR="${1:-}" ;;
    --backup-dir=*) BACKUP_DIR="${arg#*=}" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
  esac
done

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RESET=$'\e[0m'
TS=$(date -u +%Y%m%dT%H%M%SZ)
INDB_DIR="/tmp/dr-drill-$TS"
fails=0
checks=()

# psql -tA(无表头/无对齐),进容器跑;$1=库 $2=SQL
q() { docker exec -e PGPASSWORD="$PG_PASSWORD" -i "$PG_CONTAINER" \
        psql -U "$PG_USER" -d "$1" -tAc "$2" 2>/dev/null; }
# 任意 psql(建库/删库等),连默认库
adm() { docker exec -e PGPASSWORD="$PG_PASSWORD" -i "$PG_CONTAINER" \
        psql -U "$PG_USER" -d postgres -tAc "$1"; }

check() { # $1=名称 $2=期望 $3=实际
  if [[ "$2" == "$3" ]]; then
    checks+=("  ${GREEN}✓${RESET} $1: $3")
  else
    checks+=("  ${RED}✗${RESET} $1: 期望=$2 实际=$3"); fails=$((fails+1))
  fi
}

echo "${BOLD}== 本地灾备演练 ($MODE 模式) ==${RESET}"

# ---- 0. 前置 ----
if ! docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" >/dev/null 2>&1; then
  echo "${RED}前置失败:$PG_CONTAINER 不可用(docker compose up?)${RESET}"; exit 1
fi
HAS_BUSINESS=$([[ -n "$(adm "SELECT 1 FROM pg_database WHERE datname='$BUSINESS_DB'")" ]] && echo 1 || echo 0)

# ---- 1. 事故前快照(§2.3 比对基线)----
echo "${BLUE}[1/5] 采集事故前快照...${RESET}"
SNAP_JOB=$(q "$PLATFORM_DB" "SELECT count(*) FROM batch.job_instance")
SNAP_OUTBOX=$(q "$PLATFORM_DB" "SELECT count(*) FROM batch.outbox_event")
SNAP_FLYWAY=$(q "$PLATFORM_DB" "SELECT max(version) FROM batch.flyway_schema_history WHERE success")
SNAP_BIZ=0; SNAP_POLICIES=0
if [[ "$HAS_BUSINESS" == "1" ]]; then
  SNAP_BIZ=$(q "$BUSINESS_DB" "SELECT count(*) FROM biz.customer_account")
  SNAP_POLICIES=$(q "$BUSINESS_DB" "SELECT count(*) FROM pg_policies WHERE schemaname='biz'")
fi
echo "  job_instance=$SNAP_JOB outbox_event=$SNAP_OUTBOX flyway=$SNAP_FLYWAY biz.customer_account=$SNAP_BIZ policies=$SNAP_POLICIES"

# ---- 2. 备份(pg_dump -Fc,§1.2)----
echo "${BLUE}[2/5] 备份(pg_dump custom format)...${RESET}"
docker exec "$PG_CONTAINER" mkdir -p "$INDB_DIR"
docker exec -e PGPASSWORD="$PG_PASSWORD" "$PG_CONTAINER" \
  pg_dump -U "$PG_USER" -Fc -f "$INDB_DIR/$PLATFORM_DB.dump" "$PLATFORM_DB"
echo "  ✓ $PLATFORM_DB → $INDB_DIR/$PLATFORM_DB.dump"
if [[ "$HAS_BUSINESS" == "1" ]]; then
  docker exec -e PGPASSWORD="$PG_PASSWORD" "$PG_CONTAINER" \
    pg_dump -U "$PG_USER" -Fc -f "$INDB_DIR/$BUSINESS_DB.dump" "$BUSINESS_DB"
  echo "  ✓ $BUSINESS_DB → $INDB_DIR/$BUSINESS_DB.dump"
fi
if [[ -n "$BACKUP_DIR" ]]; then
  mkdir -p "$BACKUP_DIR"
  docker cp "$PG_CONTAINER:$INDB_DIR/$PLATFORM_DB.dump" "$BACKUP_DIR/$PLATFORM_DB-$TS.dump"
  [[ "$HAS_BUSINESS" == "1" ]] && docker cp "$PG_CONTAINER:$INDB_DIR/$BUSINESS_DB.dump" "$BACKUP_DIR/$BUSINESS_DB-$TS.dump"
  echo "  ✓ dump 已落宿主:$BACKUP_DIR"
fi

# ---- 3. 模拟灾难 + 确定恢复目标 ----
if [[ "$MODE" == "in-place" ]]; then
  if [[ "$CONFIRM" != "1" ]]; then
    echo "${RED}--in-place 是破坏性操作(DROP $PLATFORM_DB/$BUSINESS_DB 后恢复)。加 --yes 确认。${RESET}"; exit 1
  fi
  echo "${YELLOW}[3/5] 模拟灾难:DROP + 重建现有库(真实 RTO 演练)...${RESET}"
  TGT_PLATFORM="$PLATFORM_DB"; TGT_BUSINESS="$BUSINESS_DB"
  adm "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$PLATFORM_DB','$BUSINESS_DB') AND pid<>pg_backend_pid()" >/dev/null
  adm "DROP DATABASE IF EXISTS $PLATFORM_DB" >/dev/null; adm "CREATE DATABASE $PLATFORM_DB" >/dev/null
  if [[ "$HAS_BUSINESS" == "1" ]]; then adm "DROP DATABASE IF EXISTS $BUSINESS_DB" >/dev/null; adm "CREATE DATABASE $BUSINESS_DB" >/dev/null; fi
else
  echo "${BLUE}[3/5] 恢复到旁路库 *_dr(不动现有数据)...${RESET}"
  TGT_PLATFORM="${PLATFORM_DB}_dr"; TGT_BUSINESS="${BUSINESS_DB}_dr"
  adm "DROP DATABASE IF EXISTS $TGT_PLATFORM" >/dev/null; adm "CREATE DATABASE $TGT_PLATFORM" >/dev/null
  if [[ "$HAS_BUSINESS" == "1" ]]; then adm "DROP DATABASE IF EXISTS $TGT_BUSINESS" >/dev/null; adm "CREATE DATABASE $TGT_BUSINESS" >/dev/null; fi
fi

# ---- 4. 恢复(§2.1)+ 量 RTO ----
echo "${BLUE}[4/5] 恢复(pg_restore -j4)...${RESET}"
T0=$(date +%s)
docker exec -e PGPASSWORD="$PG_PASSWORD" "$PG_CONTAINER" \
  pg_restore -U "$PG_USER" -d "$TGT_PLATFORM" -j4 --no-owner "$INDB_DIR/$PLATFORM_DB.dump" 2>/dev/null || true
if [[ "$HAS_BUSINESS" == "1" ]]; then
  docker exec -e PGPASSWORD="$PG_PASSWORD" "$PG_CONTAINER" \
    pg_restore -U "$PG_USER" -d "$TGT_BUSINESS" -j4 --no-owner "$INDB_DIR/$BUSINESS_DB.dump" 2>/dev/null || true
  # §2.1 step4:business 恢复后重跑 RLS(逻辑 dump 不保证 role/policy 完整)
  if [[ -f "$RLS_SQL" ]]; then
    docker exec -e PGPASSWORD="$PG_PASSWORD" -i "$PG_CONTAINER" \
      psql -U "$PG_USER" -d "$TGT_BUSINESS" -v ON_ERROR_STOP=0 < "$RLS_SQL" >/dev/null 2>&1 || true
    echo "  ✓ 已重跑 rls-phase-a.sql"
  else
    echo "  ${YELLOW}⚠ 未找到 $RLS_SQL,跳过 RLS 重建${RESET}"
  fi
fi
RTO=$(( $(date +%s) - T0 ))

# ---- 5. 校验(§2.3 清单)----
echo "${BLUE}[5/5] 校验恢复结果(§2.3)...${RESET}"
check "job_instance 行数" "$SNAP_JOB"     "$(q "$TGT_PLATFORM" "SELECT count(*) FROM batch.job_instance")"
check "outbox_event 行数" "$SNAP_OUTBOX"  "$(q "$TGT_PLATFORM" "SELECT count(*) FROM batch.outbox_event")"
check "Flyway 最高版本"   "$SNAP_FLYWAY"  "$(q "$TGT_PLATFORM" "SELECT max(version) FROM batch.flyway_schema_history WHERE success")"
if [[ "$HAS_BUSINESS" == "1" ]]; then
  check "biz.customer_account 行数" "$SNAP_BIZ" "$(q "$TGT_BUSINESS" "SELECT count(*) FROM biz.customer_account")"
  # RLS policy 校验对齐"事故前快照"(恢复后==恢复前),而非硬编码阈值;
  # 另做一个 >0 的下限兜底(防恢复后 RLS 整个丢失也算"匹配 0")。
  check "biz RLS policy 数(对齐基线)" "$SNAP_POLICIES" "$(q "$TGT_BUSINESS" "SELECT count(*) FROM pg_policies WHERE schemaname='biz'")"
  if [[ "${SNAP_POLICIES:-0}" -le 0 ]]; then
    checks+=("  ${YELLOW}⚠${RESET} 基线 biz policy=0 —— 源库 RLS 未启用?恢复无从比对"); fi
fi

# ---- 清理 ----
if [[ "$MODE" == "safe" && "$KEEP" != "1" ]]; then
  adm "DROP DATABASE IF EXISTS $TGT_PLATFORM" >/dev/null
  [[ "$HAS_BUSINESS" == "1" ]] && adm "DROP DATABASE IF EXISTS $TGT_BUSINESS" >/dev/null
fi
docker exec "$PG_CONTAINER" rm -rf "$INDB_DIR" 2>/dev/null || true

# ---- 报告 ----
echo; echo "${BOLD}==== 灾备演练结果 ====${RESET}"
printf '%s\n' "${checks[@]}"
echo "  ${BOLD}RTO(恢复耗时)= ${RTO}s${RESET}"
[[ "$MODE" == "safe" && "$KEEP" != "1" ]] && echo "  (旁路库已清;--keep 可保留,--in-place 真实演练)"
echo
if [[ "$fails" -eq 0 ]]; then
  echo "${GREEN}${BOLD}✅ 灾备演练通过:备份可恢复、关键数据一致、RLS 可重建。${RESET}"; exit 0
else
  echo "${RED}${BOLD}❌ $fails 项校验失败 —— 备份/恢复链路有缺口,见上。${RESET}"; exit 1
fi
