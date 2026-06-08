#!/usr/bin/env bash
# =========================================================
# replay-forensic-bundle.sh — 拉 forensic 证据包 → 本地 sim 回放 → SQL 比对
#
# ADR-022 forensic_export 已能导生产证据包(job_instances + batch_day_operation_audits +
# manifest + sha256),本脚本补"回放"侧:
#   1. 解包 + sha256 校验
#   2. schema 映射 + 数据还原(临时 namespace sim_replay_${exportId})
#   3. 反推 launch request,POST 到 console-api 触发 + 轮终态
#   4. 抽 replay snapshot + 调 analyze-replay.sh 做 5 维度 diff
#   5. 出 markdown 报告
#
# 用法:
#   bash scripts/local/replay-forensic-bundle.sh <bundle.zip> [--keep] [--no-launch]
#
#   --keep      跑完保留 unpacked/ 和临时 schema(默认保留出错现场;成功时也保留 unpacked 供排查)
#   --no-launch 只解包 + 还原数据,不触发 replay(用于先看 manifest)
#
# 前置(prerequisite):
#   - sim 已起(scripts/sim/02-start-sim.sh)
#   - console-api 跑在 ${CONSOLE_PORT:-18080}(scripts/local/start-all.sh)
#   - psql / unzip / jq / sha256sum(或 shasum -a 256) / curl 在 PATH
#
# 退出条件:任何步骤失败 → 立即 stop,unpacked/ 与临时 schema 留现场;exit 1。
# =========================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SQL_DIR="$ROOT_DIR/scripts/local/sql"
# shellcheck source=../lib/env-common.sh
source "$ROOT_DIR/scripts/lib/env-common.sh"

# ── 参数解析 ────────────────────────────────────────────────────────
BUNDLE=""
KEEP=0
NO_LAUNCH=0
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    --no-launch) NO_LAUNCH=1 ;;
    --help|-h)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    -*)
      echo "[fatal] 未知选项: $arg" >&2
      exit 1
      ;;
    *)
      if [[ -z "$BUNDLE" ]]; then BUNDLE="$arg"; else
        echo "[fatal] 多余位置参数: $arg" >&2; exit 1
      fi
      ;;
  esac
done

if [[ -z "$BUNDLE" ]]; then
  echo "[fatal] usage: $0 <bundle.zip> [--keep] [--no-launch]" >&2
  exit 1
fi
if [[ ! -f "$BUNDLE" ]]; then
  echo "[fatal] 找不到 bundle: $BUNDLE" >&2
  exit 1
fi

# ── 配置 ────────────────────────────────────────────────────────────
PG_HOST="${PG_HOST:-$PGHOST}"
PG_PORT="${PG_PORT:-$PGPORT}"
PG_USER="${PG_USER:-$PGUSER}"
PG_PASSWORD="${PG_PASSWORD:-$PGPASSWORD}"
PG_PLATFORM_DB="${PG_PLATFORM_DB:-$PLATFORM_DB}"
# 注意:forensic_export_log + job_instance 都在 platform 库(orchestrator 状态机库),
# 不在 business 库(worker staging)。临时 replay schema 也建在 platform 库,
# 这样 SQL 比对可以直接 JOIN batch.job_instance。
PG_BUSINESS_DB="$PG_PLATFORM_DB"
CONSOLE_BASE="${CONSOLE_BASE:-$CONSOLE_BASE_URL}"
INTERNAL_SECRET="${INTERNAL_SECRET:-$BATCH_INTERNAL_SECRET}"
WAIT_TIMEOUT_SEC="${REPLAY_WAIT_TIMEOUT_SEC:-300}"
WAIT_INTERVAL_SEC="${REPLAY_WAIT_INTERVAL_SEC:-3}"

export PGPASSWORD="$PG_PASSWORD"

# 报告输出目录
WORK_BASE="${REPLAY_WORK_DIR:-$ROOT_DIR/build/forensic-replay}"
mkdir -p "$WORK_BASE"

# ── 日志辅助 ────────────────────────────────────────────────────────
log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
fail() { printf '[fatal] %s\n' "$*" >&2; exit 1; }

# 算 sha256 兼容 macOS / Linux
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail "no sha256sum/shasum available"
  fi
}

psql_business() {
  psql -X -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_BUSINESS_DB" "$@"
}
psql_platform() {
  psql -X -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_PLATFORM_DB" "$@"
}

# ── 前置检查 ────────────────────────────────────────────────────────
for cmd in unzip jq curl psql python3; do
  command -v "$cmd" >/dev/null 2>&1 || fail "缺少依赖: $cmd"
done

# console-api 健康
if [[ "$NO_LAUNCH" -eq 0 ]]; then
  http_code=$(curl -s -o /dev/null -w '%{http_code}' "$CONSOLE_BASE/actuator/health" || echo 000)
  if [[ "$http_code" != "200" ]]; then
    log "WARN: console-api 健康检查 $http_code(若未起请先 scripts/local/start-all.sh)"
    log "      可加 --no-launch 跳过 replay 触发,仅做解包 / 数据还原"
  fi
fi

# ── Step 1:解包 + sha256 校验 ─────────────────────────────────────
BUNDLE_ABS="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"
BUNDLE_NAME="$(basename "$BUNDLE_ABS" .zip)"
WORK_DIR="$WORK_BASE/$BUNDLE_NAME.$$"
UNPACKED="$WORK_DIR/unpacked"
mkdir -p "$UNPACKED"
log "Step 1/5 解包 + 校验  → $WORK_DIR"
unzip -q "$BUNDLE_ABS" -d "$UNPACKED" || fail "unzip 失败"

for f in manifest.json job-instances.json batch-day-operation-audits.json; do
  [[ -f "$UNPACKED/$f" ]] || fail "bundle 缺文件: $f"
done

EXPORT_ID=$(jq -r '.exportId' "$UNPACKED/manifest.json")
TENANT_ID=$(jq -r '.tenantId' "$UNPACKED/manifest.json")
BIZ_FROM=$(jq -r '.bizDateFrom' "$UNPACKED/manifest.json")
BIZ_TO=$(jq -r '.bizDateTo' "$UNPACKED/manifest.json")
JOB_CODES_JSON=$(jq -c '.jobCodes // []' "$UNPACKED/manifest.json")

[[ "$EXPORT_ID" != "null" && -n "$EXPORT_ID" ]] || fail "manifest.exportId 缺失"
[[ "$TENANT_ID" != "null" && -n "$TENANT_ID" ]] || fail "manifest.tenantId 缺失"

log "  exportId=$EXPORT_ID tenantId=$TENANT_ID bizDate=[$BIZ_FROM..$BIZ_TO] jobCodes=$JOB_CODES_JSON"

# bundle 自身 sha256:对照 forensic_export_log.sha256(如果能连 platform 库)
BUNDLE_SHA=$(sha256_file "$BUNDLE_ABS")
log "  bundle sha256=$BUNDLE_SHA"

# 尝试比对 forensic_export_log(可选,失败仅 warn)
REGISTERED_SHA=$(psql_platform -tA \
  -v tenant_id="$TENANT_ID" \
  -v export_id="$EXPORT_ID" \
  -f "$SQL_DIR/select-forensic-export-sha.sql" 2>/dev/null | tr -d '[:space:]' || true)
if [[ -n "$REGISTERED_SHA" && "$REGISTERED_SHA" != "$BUNDLE_SHA" ]]; then
  log "  WARN: 本地 platform 库 forensic_export_log.sha256=$REGISTERED_SHA 与文件 sha256 不一致(可能 bundle 来自异机,跳过强校验)"
elif [[ -n "$REGISTERED_SHA" ]]; then
  log "  ✓ sha256 与 forensic_export_log 一致"
fi

INSTANCE_ROWS=$(jq 'length' "$UNPACKED/job-instances.json")
AUDIT_ROWS=$(jq 'length' "$UNPACKED/batch-day-operation-audits.json")
log "  rows: job_instances=$INSTANCE_ROWS, batch_day_audits=$AUDIT_ROWS"

# ── Step 2:schema 映射 + 临时 ns 导入 ─────────────────────────────
# 临时 schema 名(避免 - 和大写)
SAFE_EXPORT=$(echo "$EXPORT_ID" | tr -c 'a-zA-Z0-9' '_' | tr 'A-Z' 'a-z' | sed 's/_*$//; s/^_*//')
REPLAY_SCHEMA="sim_replay_${SAFE_EXPORT}"

log "Step 2/5 创建临时 schema $REPLAY_SCHEMA + 装载 forensic snapshot"

# 用 JSONB 直存,避开 CSV → row schema 映射风险(forensic 实际产物是 JSON);
# 报告侧通过 jq + SQL 跨 schema 比对(snapshot vs 当前 batch 库 job_instance)。
psql_business -v replay_schema="$REPLAY_SCHEMA" \
  -f "$SQL_DIR/create-forensic-replay-schema.sql" || fail "创建临时 schema 失败"

# 把 forensic JSON 灌进 snapshot 表:逐行 jq 拆出 instance_no/job_code/biz_date + 整行 jsonb
log "  装载 job_instances …"
jq -c '.[]' "$UNPACKED/job-instances.json" | while IFS= read -r row; do
  ino=$(echo "$row" | jq -r '.instanceNo // .instance_no // empty')
  jc=$(echo "$row"  | jq -r '.jobCode // .job_code // empty')
  bd=$(echo "$row"  | jq -r '.bizDate // .biz_date // empty')
  tid=$(echo "$row" | jq -r '.tenantId // .tenant_id // empty')
  [[ -z "$ino" ]] && continue
  [[ "$bd" == "null" ]] && bd=""
  psql_business \
    -v replay_schema="$REPLAY_SCHEMA" \
    -v instance_no="$ino" \
    -v tenant_id="$tid" \
    -v job_code="$jc" \
    -v biz_date="$bd" \
    -v snapshot="$row" \
    -f "$SQL_DIR/insert-forensic-job-instance.sql" \
    >/dev/null || fail "插入 forensic_job_instances 失败 instance_no=$ino"
done

log "  装载 batch_day_audits …"
jq -c '.[]' "$UNPACKED/batch-day-operation-audits.json" | while IFS= read -r row; do
  rid=$(echo "$row" | jq -r '.id // empty')
  tid=$(echo "$row" | jq -r '.tenantId // .tenant_id // empty')
  [[ "$rid" == "null" ]] && rid=""
  psql_business \
    -v replay_schema="$REPLAY_SCHEMA" \
    -v audit_id="$rid" \
    -v tenant_id="$tid" \
    -v snapshot="$row" \
    -f "$SQL_DIR/insert-forensic-day-audit.sql" \
    >/dev/null || true
done

log "  ✓ 临时 schema 装载完成"

# ── Step 3:反推 launch 请求 + 触发 + 轮终态 ────────────────────────
REPORT_MD="$WORK_DIR/replay-report.md"
REPLAY_SNAPSHOT="$WORK_DIR/replay-snapshot.json"

if [[ "$NO_LAUNCH" -eq 1 ]]; then
  log "Step 3/5 --no-launch 模式:跳过 replay 触发"
  echo "[]" > "$REPLAY_SNAPSHOT"
else
  log "Step 3/5 触发 replay(console-api $CONSOLE_BASE)"

  # 从 forensic snapshot 取去重的 (jobCode, bizDate) 二元组
  TRIGGER_PAIRS=$(psql_business -tA -F'|' \
    -v replay_schema="$REPLAY_SCHEMA" \
    -v tenant_id="$TENANT_ID" \
    -f "$SQL_DIR/select-forensic-trigger-pairs.sql")

  if [[ -z "$TRIGGER_PAIRS" ]]; then
    log "  WARN: forensic snapshot 无可触发 (jobCode,bizDate) 对,跳过"
  fi

  TRIGGER_COUNT=0
  TRIGGERED_INSTANCES=()
  while IFS='|' read -r jc bd; do
    [[ -z "$jc" || -z "$bd" ]] && continue
    payload=$(jq -nc \
      --arg t "$TENANT_ID" --arg j "$jc" --arg d "$bd" \
      '{tenantId:$t, jobCode:$j, bizDate:$d, triggerType:"MANUAL", dryRun:false}')
    idem_key="replay-$EXPORT_ID-$jc-$bd"
    log "  POST /api/console/jobs/trigger jobCode=$jc bizDate=$bd"
    resp=$(curl -sS -X POST "$CONSOLE_BASE/api/console/jobs/trigger" \
      -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $idem_key" \
      -H "X-Internal-Secret: $INTERNAL_SECRET" \
      --data "$payload" 2>&1) || {
        log "  WARN: 触发失败 jobCode=$jc:$resp"
        continue
      }
    log "  → $resp"
    TRIGGER_COUNT=$((TRIGGER_COUNT+1))
    TRIGGERED_INSTANCES+=("$jc|$bd")
  done <<< "$TRIGGER_PAIRS"

  log "  共触发 $TRIGGER_COUNT 个 (jobCode,bizDate) 对,轮询终态…"

  # 轮询 instance_status:每个 (jc,bd) 看是否出现 replay 新行 + 进终态
  TERMINAL_RE="SUCCESS|FAILED|PARTIAL_FAILED|CANCELED|SKIPPED"
  deadline=$(( $(date +%s) + WAIT_TIMEOUT_SEC ))
  while (( $(date +%s) < deadline )); do
    pending=0
    for pair in "${TRIGGERED_INSTANCES[@]:-}"; do
      [[ -z "$pair" ]] && continue
      jc="${pair%|*}"; bd="${pair#*|}"
      st=$(psql_business -tA \
        -v tenant_id="$TENANT_ID" \
        -v job_code="$jc" \
        -v biz_date="$bd" \
        -f "$SQL_DIR/select-latest-replay-instance-status.sql" 2>/dev/null | tr -d '[:space:]')
      if [[ -z "$st" ]] || ! echo "$st" | grep -qE "^($TERMINAL_RE)$"; then
        pending=$((pending+1))
      fi
    done
    if (( pending == 0 )); then
      log "  ✓ 全部进终态"
      break
    fi
    log "  …还在跑 $pending 个,sleep ${WAIT_INTERVAL_SEC}s"
    sleep "$WAIT_INTERVAL_SEC"
  done

  # 抽 replay snapshot:每个 (jobCode,bizDate) 取最新一条 batch.job_instance(即 replay 新跑的)
  log "  抽取 replay snapshot → $REPLAY_SNAPSHOT"
  psql_business -tA \
    -v tenant_id="$TENANT_ID" \
    -v biz_from="$BIZ_FROM" \
    -v biz_to="$BIZ_TO" \
    -f "$SQL_DIR/select-replay-snapshot.sql" > "$REPLAY_SNAPSHOT" 2>/dev/null || echo "[]" > "$REPLAY_SNAPSHOT"

  # 空结果占位
  [[ -s "$REPLAY_SNAPSHOT" ]] || echo "[]" > "$REPLAY_SNAPSHOT"
  if grep -q '^$' "$REPLAY_SNAPSHOT" 2>/dev/null; then echo "[]" > "$REPLAY_SNAPSHOT"; fi
fi

# ── Step 4:diff(委托 analyze-replay.sh) ────────────────────────
log "Step 4/5 调 analyze-replay.sh 做 5 维度 diff"
ANALYZE_OUT="$WORK_DIR/diff.json"
bash "$ROOT_DIR/scripts/local/analyze-replay.sh" \
  --prod "$UNPACKED/job-instances.json" \
  --replay "$REPLAY_SNAPSHOT" \
  --out "$ANALYZE_OUT" \
  || fail "analyze-replay.sh 失败,见 $ANALYZE_OUT"

# ── Step 5:生成 markdown 报告 ────────────────────────────────────
log "Step 5/5 生成报告 $REPORT_MD"
TOTAL=$(jq -r '.summary.total' "$ANALYZE_OUT")
IDENTICAL=$(jq -r '.summary.identical' "$ANALYZE_OUT")
STATUS_CHANGED=$(jq -r '.summary.statusChanged' "$ANALYZE_OUT")
COUNT_DRIFT=$(jq -r '.summary.countDrift' "$ANALYZE_OUT")
TIME_DRIFT=$(jq -r '.summary.timeDrift' "$ANALYZE_OUT")
ERROR_CHANGED=$(jq -r '.summary.errorChanged' "$ANALYZE_OUT")
PAYLOAD_DRIFT=$(jq -r '.summary.payloadDrift' "$ANALYZE_OUT")
MISSING=$(jq -r '.summary.missingInReplay' "$ANALYZE_OUT")

{
  echo "# Replay Report — exportId=$EXPORT_ID, bizDate=$BIZ_FROM..$BIZ_TO"
  echo
  echo "- tenantId: \`$TENANT_ID\`"
  echo "- bundle: \`$BUNDLE_ABS\`"
  echo "- sha256: \`$BUNDLE_SHA\`"
  echo "- 临时 schema: \`$REPLAY_SCHEMA\` (位于 $PG_BUSINESS_DB)"
  echo "- forensic rows: job_instances=$INSTANCE_ROWS, audits=$AUDIT_ROWS"
  echo
  echo "## Summary"
  echo "- Total compared: $TOTAL"
  echo "- Identical: $IDENTICAL"
  echo "- Status changed: **$STATUS_CHANGED** (最严重 — 代码改判)"
  echo "- Count drift > 5%: $COUNT_DRIFT"
  echo "- Time drift > 20%: $TIME_DRIFT"
  echo "- Error code/message changed: $ERROR_CHANGED"
  echo "- Payload JSONB structural drift: $PAYLOAD_DRIFT"
  echo "- Missing in replay (forensic 有 / replay 无): $MISSING"
  echo
  echo "## Detail"
  echo
  echo "### Status changes"
  jq -r '.details[] | select(.statusDiff) | "- \(.jobCode) bizDate=\(.bizDate): prod=\(.prodStatus) replay=\(.replayStatus)"' "$ANALYZE_OUT"
  echo
  echo "### Count drift (>5%)"
  jq -r '.details[] | select(.countDriftPct != null and (.countDriftPct|fabs) > 5) | "- \(.jobCode) bizDate=\(.bizDate): prod=\(.prodCount) replay=\(.replayCount) drift=\(.countDriftPct)%"' "$ANALYZE_OUT"
  echo
  echo "### Time drift (>20%)"
  jq -r '.details[] | select(.timeDriftPct != null and (.timeDriftPct|fabs) > 20) | "- \(.jobCode) bizDate=\(.bizDate): prod=\(.prodDurationSec)s replay=\(.replayDurationSec)s drift=\(.timeDriftPct)%"' "$ANALYZE_OUT"
  echo
  echo "### Error code/message changes"
  jq -r '.details[] | select(.errorDiff) | "- \(.jobCode) bizDate=\(.bizDate): prod=\(.prodError) replay=\(.replayError)"' "$ANALYZE_OUT"
  echo
  echo "### Payload JSONB structural drift"
  jq -r '.details[] | select(.payloadDiff) | "- " + (.jobCode|tostring) + " bizDate=" + (.bizDate|tostring) + ": " + (.payloadDiffPaths|join(", "))' "$ANALYZE_OUT"
  echo
  echo "### Missing in replay"
  jq -r '.details[] | select(.replayStatus == null) | "- \(.jobCode) bizDate=\(.bizDate) (prod status=\(.prodStatus))"' "$ANALYZE_OUT"
  echo
  echo "---"
  echo "_Generated $(date -Iseconds) by replay-forensic-bundle.sh_"
} > "$REPORT_MD"

log "✓ 报告: $REPORT_MD"
log "  原始 diff:  $ANALYZE_OUT"
log "  解包目录:   $UNPACKED  (保留供排查)"
log "  临时 schema:$REPLAY_SCHEMA  (跑完未自动 drop;清理:DROP SCHEMA $REPLAY_SCHEMA CASCADE)"

# 退出码:有 status_changed 或 error_changed → 非 0,给 CI 用
if [[ "$STATUS_CHANGED" -gt 0 || "$ERROR_CHANGED" -gt 0 ]]; then
  log "WARN: 出现 status/error 改判,exit 2(代码可能改判)"
  exit 2
fi
exit 0
