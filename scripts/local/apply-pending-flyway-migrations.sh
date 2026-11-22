#!/usr/bin/env bash
# 用途：通过 Flyway 校验并应用本地平台库迁移。
# 用法：在仓库根目录执行 bash scripts/local/apply-pending-flyway-migrations.sh；连接参数与本地 JVM 一致。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/env-common.sh
source scripts/lib/env-common.sh
batch_configure_local_jvm_database_env

flyway_classpath="$(mktemp)"
trap 'rm -f "$flyway_classpath"' EXIT

./mvnw -q -pl batch-orchestrator dependency:build-classpath \
  -Dmdep.outputFile="$flyway_classpath"
java -cp "$(cat "$flyway_classpath")" scripts/ci/FlywayMigrate.java \
  "$BATCH_PLATFORM_DB_URL" \
  "$BATCH_PLATFORM_DB_USERNAME" \
  "$BATCH_PLATFORM_DB_PASSWORD"
