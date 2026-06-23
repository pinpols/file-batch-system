package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase A 守护:rls-phase-a.sql / rls-phase-a-strict.sql / rls-phase-a-rollback-to-transition.sql 三脚本与
 * {@link RlsClosedWorldChecker#REFERENCE_RLS_TABLES} 必须**双向**一致 — 既防新加业务表只改 healthcheck 清单忘记写
 * migration,也防只往脚本加表忘了更新清单(2026-06-09 process_event_copy 漏报正是后者:旧版单向校验放过了)。
 */
class RlsPhaseAMigrationCoverageTest {

  @Test
  @DisplayName("rls-phase-a.sql 必须列出 REFERENCE_RLS_TABLES 内全部 biz/batch 表")
  void transitionMigrationCoversAllExpectedTables() throws IOException {
    assertMigrationCoversAll("rls-phase-a.sql");
  }

  @Test
  @DisplayName("rls-phase-a-strict.sql 必须列出 REFERENCE_RLS_TABLES 内全部 biz/batch 表")
  void strictMigrationCoversAllExpectedTables() throws IOException {
    assertMigrationCoversAll("rls-phase-a-strict.sql");
  }

  @Test
  @DisplayName("rls-phase-a-rollback-to-transition.sql 必须列出 REFERENCE_RLS_TABLES 内全部 biz/batch 表")
  void rollbackMigrationCoversAllExpectedTables() throws IOException {
    assertMigrationCoversAll("rls-phase-a-rollback-to-transition.sql");
  }

  /** 匹配脚本 tables 数组里的表字面量 'biz.xxx' / 'batch.xxx'(排除 policy 名等其他引号串)。 */
  private static final Pattern TABLE_LITERAL = Pattern.compile("'((?:biz|batch)\\.[a-z_]+)'");

  private void assertMigrationCoversAll(String filename) throws IOException {
    Path script =
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("scripts/db/business/" + filename);
    assertThat(script).as(filename + " 必须存在").exists();
    String sql = Files.readString(script);

    // 正向:REFERENCE_RLS_TABLES ⊆ 脚本
    List<String> missing = new ArrayList<>();
    for (String t : RlsClosedWorldChecker.REFERENCE_RLS_TABLES) {
      if (!sql.contains("'" + t + "'") && !sql.contains(t)) {
        missing.add(t);
      }
    }
    assertThat(missing)
        .as("以下 REFERENCE_RLS_TABLES 在 " + filename + " 内找不到 — 加新表必须同步更新 3 个 migration")
        .isEmpty();

    // 反向:脚本 tables 数组里的每张表 ⊆ REFERENCE_RLS_TABLES(防止脚本加表忘了更新清单)
    List<String> notInExpected = new ArrayList<>();
    Matcher m = TABLE_LITERAL.matcher(sql);
    while (m.find()) {
      String t = m.group(1);
      if (!RlsClosedWorldChecker.REFERENCE_RLS_TABLES.contains(t) && !notInExpected.contains(t)) {
        notInExpected.add(t);
      }
    }
    assertThat(notInExpected)
        .as(filename + " 列了以下表但 REFERENCE_RLS_TABLES 没有 — 往脚本加表必须同步更新 healthcheck 清单")
        .isEmpty();
  }
}
