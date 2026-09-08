#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# 历史内联 SQL 的命中行数预算。预算只能减少，不能增加；未登记文件预算为 0。
# 本 CI 守护脚本仅保存检测表达式，不执行 SQL，故不计入历史债务。
legacy_statement_budget() {
  case "$1" in
    scripts/ci/check-sql-config-boundaries.sh) echo exempt ;;
    scripts/ci/run-sdk-orchestrator-e2e.sh) echo 5 ;;
    scripts/db/backup/dr-drill.sh) echo 12 ;;
    scripts/lib/sdk-e2e-common.sh) echo 19 ;;
    scripts/local/adr046-batch-consume-load.sh) echo 5 ;;
    scripts/local/sim-harness.sh) echo 11 ;;
    scripts/local/validate-seed-scenarios.sh) echo 53 ;;
    scripts/sim/00-reset-runtime.sh) echo 4 ;;
    scripts/sim/98-quiesce-schedules.sh) echo 7 ;;
    *) echo 0 ;;
  esac
}

matched_statement_count() {
  awk -v pattern="$PATTERN" '
    $0 ~ pattern \
      && $0 !~ /^[[:space:]]*#/ \
      && $0 !~ /^[[:space:]]*(echo|log|printf|curl)[[:space:]]/ { count++ }
    END { print count + 0 }
  ' "$1"
}

# 用 grep(coreutils,处处可用)而非 rg —— GitHub runner 不一定装 ripgrep,
# 之前 rg 缺失时本检查静默 no-op(offenders 空 → 永远 passed,假绿)。改 grep 杜绝。
PATTERN="<<'?SQL|jsonb_build_object|psql[[:space:]][^#]*[[:space:]]-c([[:space:]]|$)|SELECT |INSERT |UPDATE |DELETE |ALTER TABLE|DROP TABLE|CREATE TABLE"
fail=0
while IFS= read -r file; do
  budget="$(legacy_statement_budget "$file")"
  [[ "$budget" == "exempt" ]] && continue

  current="$(matched_statement_count "$file")"
  if (( current > budget )); then
    echo "SQL/config boundary violation: $file has $current matched line(s), budget=$budget" >&2
    fail=1
  fi
done < <(grep -rlE "$PATTERN" --include='*.sh' scripts load-tests 2>/dev/null | sort)

if [[ "$fail" -ne 0 ]]; then
  cat >&2 <<'MSG'

不要在新的 shell 脚本里内联 SQL 或 JSON 配置，也不要增加历史文件的内联语句预算。
推荐放置位置:
  - sim/test fixture: docs/test-data/*.sql
  - load test SQL: load-tests/sql/*.sql
  - local helper SQL: scripts/local/sql/*.sql
  - ops SQL: scripts/ops/sql/*.sql
  - 稳定业务查询: mapper XML
  - schema 变更: db/migration
MSG
  exit 1
fi

echo "SQL/config boundary check passed"
