#!/usr/bin/env bash
# 本地开发库 Flyway history 修复工具。
#
# 用途：开发数据卷曾执行过旧版本迁移脚本，当前工作区的 V*.sql 已变化时，
# Flyway validate 会报 checksum mismatch。此脚本调用 Flyway 官方 API repair，
# 将本地 schema_history 与当前仓库迁移脚本对齐，然后立即 validate。
#
# 仅用于 local/dev 数据库；生产/预发应走正式 DBA 变更流程，不应直接 repair。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# shellcheck source=../lib/env-common.sh
source "$ROOT/scripts/lib/env-common.sh"
batch_configure_local_jvm_database_env

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CP_FILE="$TMP_DIR/classpath.txt"
"$ROOT/mvnw" -q -pl batch-orchestrator -am dependency:build-classpath \
  -Dmdep.outputFile="$CP_FILE" >/dev/null

cat >"$TMP_DIR/FbsFlywayRepair.java" <<'JAVA'
import org.flywaydb.core.Flyway;

public class FbsFlywayRepair {
  public static void main(String[] args) {
    if (args.length != 3) {
      throw new IllegalArgumentException("usage: <jdbc-url> <user> <password>");
    }
    Flyway flyway = Flyway.configure()
        .dataSource(args[0], args[1], args[2])
        .locations("filesystem:db/migration")
        .defaultSchema("batch")
        .schemas("batch", "quartz")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .load();
    flyway.repair();
    flyway.validate();
    System.out.println("flyway repair + validate OK");
  }
}
JAVA

javac -proc:none -cp "$(cat "$CP_FILE")" "$TMP_DIR/FbsFlywayRepair.java"
java -cp "$TMP_DIR:$(cat "$CP_FILE")" FbsFlywayRepair \
  "$BATCH_PLATFORM_DB_URL" \
  "$BATCH_PLATFORM_DB_USERNAME" \
  "$BATCH_PLATFORM_DB_PASSWORD"
