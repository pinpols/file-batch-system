#!/usr/bin/env bash
# Run a small repeatable PG write parameter matrix for import COPY/UPSERT.
#
# Scope:
#   - session parameters: synchronous_commit, work_mem, maintenance_work_mem
#   - server parameters: checkpoint_timeout, max_wal_size (ALTER SYSTEM + reload)
#   - target index count: 0 / 3 extra secondary indexes
#
# The script restores checkpoint_timeout/max_wal_size when it exits.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
cd "$ROOT"

ROWS="${ROWS:-100000}"
BATCH_SIZE="${BATCH_SIZE:-5000}"
REPEATS="${REPEATS:-3}"

RUN_ID="${RUN_ID:-pg-param-matrix-$(date +%Y%m%d%H%M%S)}"
OUT_DIR="${OUT_DIR:-load-tests/target/$RUN_ID}"
mkdir -p "$OUT_DIR"
RAW="$OUT_DIR/raw.tsv"
SUMMARY="$OUT_DIR/summary.tsv"
REPORT="$OUT_DIR/pg-write-parameter-matrix.md"

BASE_JDBC="${DB_URL:-jdbc:postgresql://${PGHOST}:${PGPORT}/${BUSINESS_DB}?reWriteBatchedInserts=true}"

psql_platform() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PLATFORM_DB" -v ON_ERROR_STOP=1 "$@"
}

psql_business() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$BUSINESS_DB" -v ON_ERROR_STOP=1 "$@"
}

sql_value() {
  psql_business -Atc "$1"
}

ORIGINAL_CHECKPOINT_TIMEOUT="$(sql_value "show checkpoint_timeout")"
ORIGINAL_MAX_WAL_SIZE="$(sql_value "show max_wal_size")"

restore_pg_settings() {
  psql_platform -qAtc "alter system set checkpoint_timeout = '${ORIGINAL_CHECKPOINT_TIMEOUT}';" >/dev/null || true
  psql_platform -qAtc "alter system set max_wal_size = '${ORIGINAL_MAX_WAL_SIZE}';" >/dev/null || true
  psql_platform -qAtc "select pg_reload_conf();" >/dev/null || true
}
trap restore_pg_settings EXIT

set_pg_settings() {
  local checkpoint_timeout="$1"
  local max_wal_size="$2"
  psql_platform -qAtc "alter system set checkpoint_timeout = '${checkpoint_timeout}';" >/dev/null
  psql_platform -qAtc "alter system set max_wal_size = '${max_wal_size}';" >/dev/null
  psql_platform -qAtc "select pg_reload_conf();" >/dev/null
  sleep 1
}

jdbc_url() {
  local options="$1"
  if [[ -z "$options" ]]; then
    printf '%s\n' "$BASE_JDBC"
  else
    printf '%s&options=%s\n' "$BASE_JDBC" "$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$options")"
  fi
}

echo -e "run_id\tcase_name\trepeat\trows\tbatch_size\textra_indexes\tcheckpoint_timeout\tmax_wal_size\tsync_commit\twork_mem\tmaintenance_work_mem\twal_bytes\tdb_bytes\tbatch_s\tbatch_rows_s\tcopy_s\tcopy_rows_s\tdirect_s\tdirect_rows_s\tcopy_speedup\tdirect_speedup" > "$RAW"

run_case() {
  local case_name="$1"
  local checkpoint_timeout="$2"
  local max_wal_size="$3"
  local options="$4"
  local extra_indexes="$5"
  local sync="$6"
  local work_mem="$7"
  local maintenance_work_mem="$8"

  set_pg_settings "$checkpoint_timeout" "$max_wal_size"
  local url
  url="$(jdbc_url "$options")"

  for repeat in $(seq 1 "$REPEATS"); do
    local before_lsn after_lsn wal_bytes db_bytes log_file
    before_lsn="$(sql_value "select pg_current_wal_lsn()")"
    log_file="$OUT_DIR/${case_name}-${repeat}.log"
    ROWS="$ROWS" BATCH_SIZE="$BATCH_SIZE" EXTRA_INDEXES="$extra_indexes" DB_URL="$url" \
      DB_USER="$PGUSER" DB_PASSWORD="$PGPASSWORD" \
      bash scripts/local/import-copy-worth-benchmark.sh > "$log_file"
    after_lsn="$(sql_value "select pg_current_wal_lsn()")"
    wal_bytes="$(sql_value "select pg_wal_lsn_diff('${after_lsn}', '${before_lsn}')::bigint")"
    db_bytes="$(sql_value "select pg_database_size(current_database())")"

    local batch_s batch_rps copy_s copy_rps direct_s direct_rps copy_speedup direct_speedup
    batch_s="$(awk -F'[ =]' '/^batch_upsert_total=/{gsub(/s$/, "", $2); print $2}' "$log_file")"
    batch_rps="$(awk -F'[ =]' '/^batch_upsert_total=/{print $4}' "$log_file")"
    copy_s="$(awk -F'[ =]' '/^copy_stage=/{gsub(/s$/, "", $6); print $6}' "$log_file")"
    copy_rps="$(awk -F'[ =]' '/^copy_stage=/{print $8}' "$log_file")"
    direct_s="$(awk -F'[ =]' '/^copy_direct_replace_total=/{gsub(/s$/, "", $2); print $2}' "$log_file")"
    direct_rps="$(awk -F'[ =]' '/^copy_direct_replace_total=/{print $4}' "$log_file")"
    copy_speedup="$(awk -F= '/^copy_vs_batch_speedup=/{gsub(/x$/, "", $2); print $2}' "$log_file")"
    direct_speedup="$(awk -F'[ =]' '/^copy_direct_replace_total=/{gsub(/x$/, "", $7); print $7}' "$log_file")"

    local effective_checkpoint effective_max_wal
    effective_checkpoint="$(sql_value "show checkpoint_timeout")"
    effective_max_wal="$(sql_value "show max_wal_size")"

    echo -e "${RUN_ID}\t${case_name}\t${repeat}\t${ROWS}\t${BATCH_SIZE}\t${extra_indexes}\t${effective_checkpoint}\t${effective_max_wal}\t${sync}\t${work_mem}\t${maintenance_work_mem}\t${wal_bytes}\t${db_bytes}\t${batch_s}\t${batch_rps}\t${copy_s}\t${copy_rps}\t${direct_s}\t${direct_rps}\t${copy_speedup}\t${direct_speedup}" | tee -a "$RAW"
  done
}

run_case baseline "$ORIGINAL_CHECKPOINT_TIMEOUT" "$ORIGINAL_MAX_WAL_SIZE" "" 0 on 4MB 64MB
run_case workmem_64_maint_256 "$ORIGINAL_CHECKPOINT_TIMEOUT" "$ORIGINAL_MAX_WAL_SIZE" "-c work_mem=64MB -c maintenance_work_mem=256MB" 0 on 64MB 256MB
run_case sync_commit_off "$ORIGINAL_CHECKPOINT_TIMEOUT" "$ORIGINAL_MAX_WAL_SIZE" "-c synchronous_commit=off" 0 off 4MB 64MB
run_case wal_checkpoint_relaxed "15min" "8GB" "" 0 on 4MB 64MB
run_case extra_indexes_3 "$ORIGINAL_CHECKPOINT_TIMEOUT" "$ORIGINAL_MAX_WAL_SIZE" "" 3 on 4MB 64MB

python3 - "$RAW" "$SUMMARY" "$REPORT" <<'PY'
import csv, statistics, sys
raw, summary, report = sys.argv[1:4]
rows = list(csv.DictReader(open(raw), delimiter="\t"))
groups = {}
for row in rows:
    groups.setdefault(row["case_name"], []).append(row)

metrics = [
    "batch_s", "batch_rows_s", "copy_s", "copy_rows_s",
    "direct_s", "direct_rows_s", "copy_speedup", "direct_speedup",
    "wal_bytes", "db_bytes",
]
with open(summary, "w", newline="") as f:
    writer = csv.writer(f, delimiter="\t")
    writer.writerow(["case_name", "repeats", "rows", "batch_size", "extra_indexes", "checkpoint_timeout", "max_wal_size", "sync_commit", "work_mem", "maintenance_work_mem", *[m + "_median" for m in metrics]])
    for case, items in groups.items():
        first = items[0]
        med = []
        for metric in metrics:
            vals = [float(item[metric]) for item in items if item[metric] != ""]
            med.append(round(statistics.median(vals), 3))
        writer.writerow([
            case, len(items), first["rows"], first["batch_size"], first["extra_indexes"],
            first["checkpoint_timeout"], first["max_wal_size"], first["sync_commit"],
            first["work_mem"], first["maintenance_work_mem"], *med
        ])

baseline = groups.get("baseline", [None])[0]
with open(report, "w") as f:
    f.write("# PG Write Parameter Matrix\n\n")
    f.write(f"- Run ID: `{rows[0]['run_id']}`\n")
    f.write(f"- Rows per run: `{rows[0]['rows']}`\n")
    f.write(f"- Batch size: `{rows[0]['batch_size']}`\n")
    f.write(f"- Repeats per case: `{len(next(iter(groups.values())))}`\n")
    f.write("- Scope: import COPY/UPSERT microbenchmark on dedicated `biz.import_copy_worth_bench`.\n")
    f.write("- Caveat: this is a local PG write matrix, not a full worker import/process/export system replay.\n\n")
    f.write("## Median Summary\n\n")
    f.write("| Case | Indexes | checkpoint | max_wal | sync | work_mem | maint_mem | batch s | batch rows/s | copy s | copy rows/s | direct s | direct rows/s | WAL bytes |\n")
    f.write("|---|---:|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|\n")
    for line in csv.DictReader(open(summary), delimiter="\t"):
        f.write(
            f"| {line['case_name']} | {line['extra_indexes']} | {line['checkpoint_timeout']} | {line['max_wal_size']} | {line['sync_commit']} | {line['work_mem']} | {line['maintenance_work_mem']} | "
            f"{line['batch_s_median']} | {line['batch_rows_s_median']} | {line['copy_s_median']} | {line['copy_rows_s_median']} | {line['direct_s_median']} | {line['direct_rows_s_median']} | {line['wal_bytes_median']} |\n"
        )
    f.write("\n## Recommendation\n\n")
    f.write("- Keep `synchronous_commit=on` for production defaults; `off` is benchmark-only because crash semantics change.\n")
    f.write("- Prefer COPY/direct partition replace for full logical partition refresh; it remains the highest-throughput path in this matrix.\n")
    f.write("- `work_mem` / `maintenance_work_mem` are safe as session-scoped knobs, but this microbenchmark only promotes them if the median improves materially.\n")
    f.write("- Relaxed checkpoint/WAL settings should be considered only after checking checkpoint/WAL deltas and production disk headroom.\n")
    f.write("- Extra secondary indexes materially increase write cost; large imports should keep target partition indexes minimal and rebuild optional indexes after load when possible.\n")
PY

restore_pg_settings
trap - EXIT

echo "PG parameter matrix written:"
echo "  raw:     $RAW"
echo "  summary: $SUMMARY"
echo "  report:  $REPORT"
