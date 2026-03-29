#!/usr/bin/env bash
# =========================================================
# inspect-all.sh - 全量巡检主入口
# Notes:
# 1) 依次运行所有巡检脚本并汇总结果。
# 2) 退出码 0 表示通过，1 表示至少一个脚本失败。
# =========================================================
#   1. inspect-observability.sh  — 服务 health / Prometheus 指标 / Kafka lag
#   2. inspect-db.sh             — Flyway / 告警事件 / 卡死作业 / Outbox / 死信 / 重试积压
#   3. inspect-workers.sh        — Worker 排空超时 / 心跳失联 / 孤儿任务
#
# 使用方法：
#   # 最小配置（DB 巡检 + 服务巡检）
#   PGHOST=localhost PGPORT=5432 PGDATABASE=batch_db PGUSER=batch PGPASSWORD=secret \
#   BATCH_OBSERVABILITY_BASE_URLS=http://localhost:8080,http://localhost:8082 \
#     bash scripts/local/inspect-all.sh
#
#   # 完整配置（含 Kafka lag）
#   PGHOST=... PGPASSWORD=... \
#   BATCH_OBSERVABILITY_BASE_URLS=http://localhost:8080,http://localhost:8082 \
#   BATCH_OBSERVABILITY_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
#     bash scripts/local/inspect-all.sh
#
#   # 仅巡检（跳过某个脚本）
#   BATCH_INSPECT_SKIP_OBSERVABILITY=true bash scripts/local/inspect-all.sh
#   BATCH_INSPECT_SKIP_DB=true           bash scripts/local/inspect-all.sh
#   BATCH_INSPECT_SKIP_WORKERS=true      bash scripts/local/inspect-all.sh
#
# 输出格式：
#   每个脚本的输出以 banner 分隔，最后打印汇总表。

set -uo pipefail

# ── configuration ─────────────────────────────────────────────────────────────
BATCH_INSPECT_SKIP_OBSERVABILITY="${BATCH_INSPECT_SKIP_OBSERVABILITY:-false}"
BATCH_INSPECT_SKIP_DB="${BATCH_INSPECT_SKIP_DB:-false}"
BATCH_INSPECT_SKIP_WORKERS="${BATCH_INSPECT_SKIP_WORKERS:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── helpers ───────────────────────────────────────────────────────────────────
overall_status=0
declare -A script_results=()

ts() { date '+%Y-%m-%dT%H:%M:%S'; }
banner() { printf '\n%s\n' "$(printf '=%.0s' {1..60})"; printf '=== %s  [%s]\n' "$1" "$(ts)"; printf '%s\n' "$(printf '=%.0s' {1..60})"; }

run_script() {
  local name="$1"
  local script="$2"
  local skip_flag="$3"

  if [[ "${skip_flag}" == "true" ]]; then
    script_results["${name}"]="SKIPPED"
    return
  fi

  if [[ ! -f "${script}" ]]; then
    printf 'WARN: %s not found at %s — skipping\n' "${name}" "${script}"
    script_results["${name}"]="NOT_FOUND"
    return
  fi

  banner "${name}"
  local exit_code=0
  bash "${script}" || exit_code=$?

  if [[ "${exit_code}" -eq 0 ]]; then
    script_results["${name}"]="PASSED"
  else
    script_results["${name}"]="FAILED"
    overall_status=1
  fi
}

# ── run inspections ───────────────────────────────────────────────────────────
run_script "inspect-observability" \
  "${SCRIPT_DIR}/inspect-observability.sh" \
  "${BATCH_INSPECT_SKIP_OBSERVABILITY}"

run_script "inspect-db" \
  "${SCRIPT_DIR}/inspect-db.sh" \
  "${BATCH_INSPECT_SKIP_DB}"

run_script "inspect-workers" \
  "${SCRIPT_DIR}/inspect-workers.sh" \
  "${BATCH_INSPECT_SKIP_WORKERS}"

# ── summary ───────────────────────────────────────────────────────────────────
banner "INSPECTION SUMMARY"
printf '%-30s  %s\n' "Script" "Result"
printf '%s\n' "$(printf -- '-%.0s' {1..45})"
for name in "inspect-observability" "inspect-db" "inspect-workers"; do
  result="${script_results[${name}]:-UNKNOWN}"
  printf '%-30s  %s\n' "${name}" "${result}"
done
printf '%s\n' "$(printf -- '-%.0s' {1..45})"

if [[ "${overall_status}" -ne 0 ]]; then
  printf '\nOVERALL: FAILED — review FAIL lines above\n'
  printf 'Self-healing scripts available:\n'
  printf '  heal-drain-timeout.sh  — force-offline DRAINING workers past deadline\n'
  printf '  heal-dead-letters.sh   — replay NEW dead-letter tasks\n'
  printf '  heal-retry-tasks.sh    — replay FAILED job_task tasks\n'
  printf '  heal-retry-partitions.sh — replay FAILED job_partition partitions\n'
  printf '  heal-stuck-outbox.sh   — reset stuck outbox events\n'
  exit 1
fi

printf '\nOVERALL: PASSED\n'
exit 0
