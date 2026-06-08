#!/usr/bin/env bash
# Sample scheduler / dispatch / worker backlog while Gatling is running.
#
# Output CSV columns are intentionally flat so they can be pasted into a report or plotted quickly.
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
  psql "$PGURL" -v ON_ERROR_STOP=1 -Atc "
with
ji as (
  select * from batch.job_instance where tenant_id = '${TENANT_ID}'
),
jp as (
  select p.*
    from batch.job_partition p
    join batch.job_instance i on i.tenant_id = p.tenant_id and i.id = p.job_instance_id
   where p.tenant_id = '${TENANT_ID}'
),
jt as (
  select t.*
    from batch.job_task t
    join batch.job_instance i on i.tenant_id = t.tenant_id and i.id = t.job_instance_id
   where t.tenant_id = '${TENANT_ID}'
),
oe as (
  select * from batch.outbox_event where tenant_id = '${TENANT_ID}'
),
tr as (
  select * from batch.trigger_request where tenant_id = '${TENANT_ID}'
),
fd as (
  select * from batch.file_dispatch_record where tenant_id = '${TENANT_ID}'
),
wr as (
  select * from batch.worker_registry where tenant_id = '${TENANT_ID}'
)
select concat_ws(',',
  to_char(clock_timestamp(), 'YYYY-MM-DD\"T\"HH24:MI:SS.MS'),
  (select count(*) from ji where instance_status = 'CREATED'),
  (select count(*) from ji where instance_status = 'WAITING'),
  (select count(*) from ji where instance_status = 'READY'),
  (select count(*) from ji where instance_status = 'RUNNING'),
  (select count(*) from ji where instance_status = 'SUCCESS'),
  (select count(*) from ji where instance_status = 'FAILED'),
  (select count(*) from jp where partition_status = 'CREATED'),
  (select count(*) from jp where partition_status = 'WAITING'),
  (select count(*) from jp where partition_status = 'READY'),
  (select count(*) from jp where partition_status = 'RUNNING'),
  (select count(*) from jp where partition_status = 'SUCCESS'),
  (select count(*) from jp where partition_status = 'FAILED'),
  (select count(*) from jt where task_status = 'CREATED'),
  (select count(*) from jt where task_status = 'READY'),
  (select count(*) from jt where task_status = 'RUNNING'),
  (select count(*) from jt where task_status = 'SUCCESS'),
  (select count(*) from jt where task_status = 'FAILED'),
  (select count(*) from oe where publish_status = 'NEW'),
  (select count(*) from oe where publish_status = 'PUBLISHING'),
  (select count(*) from oe where publish_status = 'PUBLISHED'),
  (select count(*) from oe where publish_status = 'FAILED'),
  (select count(*) from tr where request_status in ('PENDING', 'ACCEPTED')),
  (select count(*) from tr where request_status = 'LAUNCHED'),
  (select count(*) from fd where dispatch_status = 'CREATED'),
  (select count(*) from fd where dispatch_status = 'SENT'),
  (select count(*) from fd where dispatch_status = 'ACKED'),
  (select count(*) from fd where dispatch_status = 'FAILED'),
  (select count(*) from wr where status = 'ONLINE'),
  coalesce((select sum(current_load) from wr where status = 'ONLINE'), 0),
  coalesce((select sum(max_concurrent) from wr where status = 'ONLINE'), 0),
  coalesce((select floor(max(extract(epoch from clock_timestamp() - created_at))) from jp where partition_status = 'WAITING'), 0)
);
" >>"$OUT"
  sleep "$INTERVAL_SECONDS"
done

printf 'scheduler backlog samples written: %s\n' "$OUT"
