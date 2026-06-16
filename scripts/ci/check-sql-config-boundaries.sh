#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

allowed_file() {
  case "$1" in
    db/migration/*.sql) return 0 ;;
    */src/main/resources/mapper/*.xml) return 0 ;;
    docs/test-data/*.sql) return 0 ;;
    load-tests/sql/*.sql) return 0 ;;
    scripts/data/sql/*.sql) return 0 ;;
    scripts/dev/sql/*.sql) return 0 ;;
    scripts/local/sql/*.sql) return 0 ;;
    scripts/ops/sql/*.sql) return 0 ;;
    scripts/sim-4day/sql/*.sql) return 0 ;;
  esac

  # 历史遗留脚本白名单。白名单只允许继续存在,不代表推荐新增同类写法。
  case "$1" in
    scripts/ci/check-sql-config-boundaries.sh) return 0 ;;
    # 守护脚本:故意内联 SQL/DDL 关键词(on conflict / DROP TABLE 等)作检测模式,非真实执行。
    scripts/ci/check-db-scripts-safety.sh) return 0 ;;
    load-tests/scripts/cleanup-worker-load-data.sh) return 0 ;;
    load-tests/scripts/prepare-worker-load-data.sh) return 0 ;;
    load-tests/scripts/run-control-plane-worker-benchmark.sh) return 0 ;;
    scripts/data/load-system-test-data.sh) return 0 ;;
    scripts/db/reset_platform_flyway_history.sh) return 0 ;;
    scripts/dev/sonar-scan.sh) return 0 ;;
    scripts/dev/trigger-process-demo.sh) return 0 ;;
    scripts/local/analyze-replay.sh) return 0 ;;
    scripts/local/apply-pending-flyway-migrations.sh) return 0 ;;
    scripts/local/import-copy-worth-benchmark.sh) return 0 ;;
    scripts/local/pre-push-sdk-checks.sh) return 0 ;;
    scripts/local/replay-forensic-bundle.sh) return 0 ;;
    scripts/local/start-all.sh) return 0 ;;
    scripts/local/validate-seed-scenarios.sh) return 0 ;;
    scripts/ops/heal-dead-letters.sh) return 0 ;;
    scripts/ops/heal-drain-timeout.sh) return 0 ;;
    scripts/ops/heal-retry-partitions.sh) return 0 ;;
    scripts/ops/heal-retry-tasks.sh) return 0 ;;
    scripts/ops/heal-stuck-outbox.sh) return 0 ;;
    scripts/ops/heal-zombie-pipelines.sh) return 0 ;;
    scripts/ops/inspect-db.sh) return 0 ;;
    scripts/ops/inspect-workers.sh) return 0 ;;
    scripts/sim-4day/00-clean.sh) return 0 ;;
    scripts/sim-4day/42-run-4days-batchday.sh) return 0 ;;
    scripts/sim-4day/50-watch.sh) return 0 ;;
    scripts/sim/03-import-tenants.sh) return 0 ;;
    scripts/sim/06-sdk-worker-verify.sh) return 0 ;;
    scripts/sim/07-atomic-load.sh) return 0 ;;
    scripts/sim/09-export-stage3.sh) return 0 ;;
    scripts/sim/10-process-stage4.sh) return 0 ;;
    scripts/sim/11-import-stage2b.sh) return 0 ;;
    scripts/sim/12-export-stage3b.sh) return 0 ;;
    scripts/sim/13-process-stage4b.sh) return 0 ;;
    scripts/sim/14-dispatch-stage5b.sh) return 0 ;;
    scripts/sim/15-trigger-stage6b.sh) return 0 ;;
    # 运维/演练/本地编排脚本,内联 SQL 为只读校验探针 / DO 重置块,与上面同类。
    scripts/db/backup/dr-drill.sh) return 0 ;;
    scripts/local/provision-biz-shard.sh) return 0 ;;
    scripts/local/sim-harness.sh) return 0 ;;
    scripts/sim/00-reset-runtime.sh) return 0 ;;
    scripts/sim/98-quiesce-schedules.sh) return 0 ;;
  esac
  return 1
}

# 用 grep(coreutils,处处可用)而非 rg —— GitHub runner 不一定装 ripgrep,
# 之前 rg 缺失时本检查静默 no-op(offenders 空 → 永远 passed,假绿)。改 grep 杜绝。
PATTERN="<<'?SQL|jsonb_build_object|psql[[:space:]][^#]*[[:space:]]-c([[:space:]]|$)|SELECT |INSERT |UPDATE |DELETE |ALTER TABLE|DROP TABLE|CREATE TABLE"
mapfile -t offenders < <(
  grep -rlE "$PATTERN" --include='*.sh' scripts load-tests 2>/dev/null | sort
)

fail=0
for file in "${offenders[@]}"; do
  if ! allowed_file "$file"; then
    echo "SQL/config boundary violation: $file" >&2
    fail=1
  fi
done

if [[ "$fail" -ne 0 ]]; then
  cat >&2 <<'MSG'

不要在新的 shell 脚本里内联大段 SQL 或 JSON 配置。
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
