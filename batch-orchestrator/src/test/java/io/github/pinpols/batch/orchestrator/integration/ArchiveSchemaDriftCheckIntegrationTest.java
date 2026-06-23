package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.archive.ArchiveSchemaDriftCheck;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ArchiveSchemaDriftCheck 启动期守护测试 — 模拟"运维给 batch.* 加列但忘了同步 archive.*_archive"。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
class ArchiveSchemaDriftCheckIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ArchiveSchemaDriftCheck check;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    // 把测试加的临时列删干净,防污染其他 IT
    jdbcTemplate.execute("alter table batch.outbox_event drop column if exists drift_test_col");
    // 列级类型漂移用例 — 把 tenant_id 类型还原回 varchar(64)
    jdbcTemplate.execute("alter table batch.outbox_event alter column tenant_id type varchar(64)");
  }

  @Test
  void noDriftWhenSchemasMatch() {
    // 正常状态(V71 migration 跑完后,所有冷热表 column 一致)— 不抛异常
    check.checkOnStartup();
  }

  @Test
  void driftDetectedWhenHotTableHasExtraColumn() {
    // 模拟:运维给 batch.outbox_event 加了 column,但忘了同步 archive.outbox_event_archive
    jdbcTemplate.execute("alter table batch.outbox_event add column drift_test_col varchar(64)");

    assertThatThrownBy(() -> check.checkOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("archive schema drift detected")
        .hasMessageContaining("Add migration to ALTER archive.*");
  }

  @Test
  void columnsOfReturnsExpectedColumns() {
    // 抽样验证 columnsOf 工具方法行为正确
    var hotCols = check.columnsOf("batch", "outbox_event");
    var coldCols = check.columnsOf("archive", "outbox_event_archive");
    assertThat(hotCols).isNotEmpty();
    assertThat(coldCols).isNotEmpty();
    assertThat(hotCols).containsExactlyInAnyOrderElementsOf(coldCols);
  }

  @Test
  void everyArchivedTablePairMatchesColumnByColumn() {
    // checkOnStartup 内部仅 driftCount 总数,本 case 显式 per-table 比对,失败时
    // 一眼看出哪张表漂移 + 哪些列对不上。V139/V140 新加 trigger_outbox_event /
    // dead_letter_task 后是首次显式守护。
    java.util.List<String> mismatches = new java.util.ArrayList<>();
    for (String hot : archivedTables()) {
      var hotCols = check.columnsOf("batch", hot);
      var coldCols = check.columnsOf("archive", hot + "_archive");
      if (hotCols.isEmpty() && coldCols.isEmpty()) {
        // 表本身不存在 — skip(与 checkOnStartup 行为一致)
        continue;
      }
      var missingInCold = new java.util.TreeSet<>(hotCols);
      missingInCold.removeAll(coldCols);
      var missingInHot = new java.util.TreeSet<>(coldCols);
      missingInHot.removeAll(hotCols);
      if (!missingInCold.isEmpty() || !missingInHot.isEmpty()) {
        mismatches.add(hot + " | hot extra=" + missingInCold + " | cold extra=" + missingInHot);
      }
    }
    assertThat(mismatches).as("ARCHIVED_TABLES 中所有冷热表 column 必须一字不差,任一不匹配立即列出全部 diff").isEmpty();
  }

  @Test
  void everyArchiveTableHasPrimaryKey() {
    // V71 DO $$ 块在每张 archive.*_archive 上加 pk_*_archive 约束,本 case 验证
    // 后续 migration 加新归档表(V139 trigger_outbox_event_archive / V140 dead_letter_task_archive)
    // 时是否也按同款式补了 PK。无 PK 会破坏归档 UPSERT 幂等(ON CONFLICT (id) DO NOTHING 找不到 target)。
    java.util.List<String> missingPk = new java.util.ArrayList<>();
    for (String hot : archivedTables()) {
      String archiveTable = hot + "_archive";
      // 表不存在时跳过(与 columnsOf 行为一致)
      var coldCols = check.columnsOf("archive", archiveTable);
      if (coldCols.isEmpty()) {
        continue;
      }
      Integer pkCount =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM pg_constraint c "
                  + "JOIN pg_class t ON t.oid = c.conrelid "
                  + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                  + "WHERE n.nspname = 'archive' AND t.relname = ? AND c.contype = 'p'",
              Integer.class,
              archiveTable);
      if (pkCount == null || pkCount == 0) {
        missingPk.add(archiveTable);
      }
    }
    assertThat(missingPk)
        .as("所有 archive.*_archive 表必须有 PRIMARY KEY(归档 UPSERT ON CONFLICT(id) 依赖)")
        .isEmpty();
  }

  @Test
  void columnTypeDriftDetectedWhenHotColumnTypeChanges() {
    // 模拟:运维 ALTER batch.outbox_event ALTER COLUMN tenant_id TYPE varchar(128),
    // 但 archive.outbox_event_archive.tenant_id 仍是 varchar(64) — 列名集合相等,
    // 但 archive INSERT 在 tenantId 超过 64 字符时会截断 / 失败,checkOnStartup() 看不到。
    jdbcTemplate.execute("alter table batch.outbox_event alter column tenant_id type varchar(128)");

    assertThatThrownBy(() -> check.checkColumnTypesOnStartup())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("archive column type drift detected");
  }

  /**
   * 通过 reflection 拿 {@code ARCHIVED_TABLES}(package-private),让 IT 不需要 prod 代码改可见性
   * 也能枚举全表。失败说明字段名/可见性变了,本测试应同步更新。
   */
  @SuppressWarnings("unchecked")
  private static java.util.List<String> archivedTables() {
    try {
      java.lang.reflect.Field f = ArchiveSchemaDriftCheck.class.getDeclaredField("ARCHIVED_TABLES");
      f.setAccessible(true);
      return (java.util.List<String>) f.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("无法访问 ArchiveSchemaDriftCheck.ARCHIVED_TABLES", e);
    }
  }
}
