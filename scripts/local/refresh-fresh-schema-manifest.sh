#!/usr/bin/env bash
# 用途：新增或修改 db/migration 后重新生成平台 fresh schema 清单，供提交前自动刷新与本地复核。
# 用法：在仓库根目录执行 bash scripts/local/refresh-fresh-schema-manifest.sh；执行后打印清单 diff，确认只包含本次迁移的对象变化。
# 说明：清单是结构漂移守卫，CI 仍按已提交清单只读校验；本脚本只负责本地重生成，不改变校验口径。
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
cd "$ROOT"

MANIFEST_DIR="batch-orchestrator/src/test/resources/db"
MANIFEST_FILE="$MANIFEST_DIR/fresh-platform-schema-manifest.sha256"

./mvnw -o -ntp -pl batch-orchestrator -am \
  '-Dtest=LocalFlywayPlatformMigrationsIntegrationTest#shouldCreatePlatformSchemaObjects_whenMigrationPathRuns' \
  '-Dsurefire.failIfNoSpecifiedTests=false' \
  -Dbatch.test.schema-manifest-update=WRITE \
  test

echo "==> fresh schema 清单已生成，请复核差异："
git --no-pager diff -- "$MANIFEST_FILE" || true
