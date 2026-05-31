package com.example.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase A 守护:scripts/db/business/rls-phase-a.sql 必须覆盖 {@link
 * RlsPolicyHealthIndicator#EXPECTED_RLS_TABLES} 全部表 — 防止新加业务表只改 healthcheck 清单忘记 写 migration,或反之。
 */
class RlsPhaseAMigrationCoverageTest {

  @Test
  @DisplayName("rls-phase-a.sql 必须列出 EXPECTED_RLS_TABLES 内全部 biz/batch 表")
  void rlsMigrationCoversAllExpectedTables() throws IOException {
    Path script =
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("scripts/db/business/rls-phase-a.sql");
    assertThat(script).as("rls-phase-a.sql 必须存在").exists();
    String sql = Files.readString(script);

    List<String> missing = new ArrayList<>();
    for (String t : RlsPolicyHealthIndicator.EXPECTED_RLS_TABLES) {
      // 短名出现即可(rls-phase-a.sql 里裸 'biz.customer_account' / format() 内)
      if (!sql.contains("'" + t + "'") && !sql.contains(t)) {
        missing.add(t);
      }
    }
    assertThat(missing)
        .as("以下 EXPECTED_RLS_TABLES 在 rls-phase-a.sql 内找不到 — 加新表必须同步更新 migration")
        .isEmpty();
  }
}
