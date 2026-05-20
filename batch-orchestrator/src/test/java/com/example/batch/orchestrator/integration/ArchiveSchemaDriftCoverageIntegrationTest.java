package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.infrastructure.archive.ArchiveSchemaDriftCheck;
import com.example.batch.testing.AbstractIntegrationTest;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ArchiveSchemaDriftCheck 反向覆盖率守护:
 *
 * <p>原 {@link ArchiveSchemaDriftCheckIntegrationTest} 守"加列漂移",但**漏掉**了"新加 archive 表但忘了在 {@code
 * ARCHIVED_TABLES} 登记"这条 — 此时启动期 check 会跳过该表,列差异无人审。
 *
 * <p>本测试做反向扫描:
 *
 * <ol>
 *   <li>DB 里所有 {@code archive.*_archive} 物理表必须在 {@code ARCHIVED_TABLES} 登记
 *   <li>{@code ARCHIVED_TABLES} 每项的 hot 表 + cold 表都必须存在
 * </ol>
 *
 * 任何一边漂移即 fail,强制新加表时同步登记。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
class ArchiveSchemaDriftCoverageIntegrationTest extends AbstractIntegrationTest {

  /** 已知未注册但故意豁免的 archive 表(临时归档 / 工具表)。 任何新增豁免必须带注释说明原因,代码审查时拒收"无故跳过登记"。 */
  private static final Set<String> EXEMPTED_ARCHIVE_TABLES = Set.of();

  @Autowired private ArchiveSchemaDriftCheck check;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void everyArchiveTableMustBeRegisteredInArchivedTables() {
    List<String> physicalArchiveTables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'archive' AND table_name LIKE '%_archive'"
                + " ORDER BY table_name",
            String.class);

    Set<String> registered = registeredArchivedTables();

    Set<String> physicalHotNames = new HashSet<>();
    for (String physical : physicalArchiveTables) {
      // archive.outbox_event_archive → hot 表名 outbox_event
      if (EXEMPTED_ARCHIVE_TABLES.contains(physical)) continue;
      String hotName = physical.substring(0, physical.length() - "_archive".length());
      physicalHotNames.add(hotName);
    }

    Set<String> missing = new HashSet<>(physicalHotNames);
    missing.removeAll(registered);
    assertThat(missing)
        .as(
            "下列 archive.*_archive 表存在于 DB 但未在 ArchiveSchemaDriftCheck.ARCHIVED_TABLES 登记 — "
                + "新加归档表必须同步登记,否则列差异静默漏过。如确为临时表请加入 EXEMPTED_ARCHIVE_TABLES 并注释原因。")
        .isEmpty();
  }

  @Test
  void everyRegisteredTableMustHaveBothHotAndColdPhysicalTables() {
    Set<String> registered = registeredArchivedTables();
    Set<String> missingHot = new HashSet<>();
    Set<String> missingCold = new HashSet<>();
    for (String name : registered) {
      if (check.columnsOf("batch", name).isEmpty()) missingHot.add(name);
      if (check.columnsOf("archive", name + "_archive").isEmpty()) missingCold.add(name);
    }
    assertThat(missingHot).as("ARCHIVED_TABLES 登记的 hot 表在 batch schema 中不存在").isEmpty();
    assertThat(missingCold).as("ARCHIVED_TABLES 登记的 cold 表在 archive schema 中不存在").isEmpty();
  }

  @Test
  void registeredCountMatchesPhysicalArchiveCount() {
    // 数量级冗余守护:登记数应与 DB 中物理 archive 表数(去豁免后)一致
    int physical =
        jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM information_schema.tables"
                    + " WHERE table_schema = 'archive' AND table_name LIKE '%_archive'",
                Integer.class)
            - EXEMPTED_ARCHIVE_TABLES.size();
    int registered = registeredArchivedTables().size();
    assertThat(registered)
        .as("ARCHIVED_TABLES 数(%d) ≠ 物理 archive.*_archive 表数(%d),登记漂移", registered, physical)
        .isEqualTo(physical);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> registeredArchivedTables() {
    try {
      Field f = ArchiveSchemaDriftCheck.class.getDeclaredField("ARCHIVED_TABLES");
      f.setAccessible(true);
      return new HashSet<>((List<String>) f.get(null));
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError("无法读取 ArchiveSchemaDriftCheck.ARCHIVED_TABLES", ex);
    }
  }
}
