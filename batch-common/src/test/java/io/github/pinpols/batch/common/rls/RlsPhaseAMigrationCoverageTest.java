package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase A 守护：三份 RLS 运维脚本必须按真实 schema 动态发现租户表，不能退回固定表清单。 新增 biz 表只要带
 * tenant_id，就会被安装脚本覆盖；非分区子表由父表继承策略。
 */
class RlsPhaseAMigrationCoverageTest {

  private static final List<String> MIGRATION_SCRIPTS =
      List.of(
          "rls-phase-a.sql", "rls-phase-a-strict.sql", "rls-phase-a-rollback-to-transition.sql");

  @Test
  @DisplayName("RLS 安装、strict、回滚脚本都动态发现 biz 租户表")
  void migrationScriptsUseDynamicTenantTableDiscovery() throws IOException {
    for (String filename : MIGRATION_SCRIPTS) {
      Path script =
          Path.of(System.getProperty("user.dir"))
              .getParent()
              .resolve("scripts/db/business/" + filename);
      assertThat(script).as(filename + " 必须存在").exists();

      String sql = Files.readString(script);
      assertThat(sql)
          .as(filename + " 必须按 tenant_id 动态发现表")
          .contains("information_schema.columns")
          .contains("column_name = 'tenant_id'")
          .contains("c.relispartition = false")
          .contains("batch.process_staging");
    }
  }
}
