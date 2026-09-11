#!/usr/bin/env bash
# 在 Gatling 运行期间采样 scheduler / dispatch / worker 的积压情况。
#
# 输出的 CSV 列特意做成扁平结构，便于直接粘贴进报告或快速绘图。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=load-tests/scripts/env.sh
source "$ROOT/load-tests/scripts/env.sh"

PGURL="${PGURL:-postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${PLATFORM_DB}}"
TENANT_ID="${TENANT_ID:-$LOAD_TEST_TENANT_ID}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"
DURATION_SECONDS="${DURATION_SECONDS:-300}"
OUT="${OUT:-target/scheduler-backlog-$(date +%Y%m%d%H%M%S).csv}"

mkdir -p "$(dirname "$OUT")"

cat >"$OUT" <<'CSV'
sample_at,ji_created,ji_waiting,ji_ready,ji_running,ji_success,ji_failed,jp_created,jp_waiting,jp_ready,jp_running,jp_success,jp_failed,jt_created,jt_ready,jt_running,jt_success,jt_failed,outbox_new,outbox_publishing,outbox_published,outbox_failed,trigger_pending,trigger_launched,dispatch_created,dispatch_sent,dispatch_acked,dispatch_failed,worker_online,worker_load,worker_capacity,oldest_waiting_partition_seconds
CSV

deadline=$((SECONDS + DURATION_SECONDS))
while (( SECONDS <= deadline )); do
  psql "$PGURL" -X -v ON_ERROR_STOP=1 -v tenant_id="$TENANT_ID" -Aqt \
    -f "$ROOT/load-tests/sql/sample-scheduler-backlog.sql" >>"$OUT"
  sleep "$INTERVAL_SECONDS"
done

printf 'scheduler backlog samples written: %s\n' "$OUT"
