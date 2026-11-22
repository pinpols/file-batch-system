#!/usr/bin/env bash
# 用途：本地开发库 Flyway history 修复工具。
# 用法：在仓库根目录执行 bash scripts/local/repair-local-flyway-history.sh；参数与前置环境见下方说明及所属 README。
# 本地开发库 Flyway history 修复工具。
#
# 说明：开发数据卷曾执行过旧版本迁移脚本，当前工作区的 V*.sql 已变化时，
# Flyway validate 会报 checksum mismatch。此脚本仅修复已经使用新编号的本地库，
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
    boolean baselineApplied = false;
    for (var info : flyway.info().applied()) {
      if (info.getVersion() != null
          && "2".equals(info.getVersion().getVersion())
          && "V2__platform_schema.sql".equals(info.getScript())
          && info.getState().isApplied()) {
        baselineApplied = true;
        break;
      }
    }
    if (!baselineApplied) {
      throw new IllegalStateException("未找到当前平台基线。请备份后使用空平台库重建。");
    }
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
