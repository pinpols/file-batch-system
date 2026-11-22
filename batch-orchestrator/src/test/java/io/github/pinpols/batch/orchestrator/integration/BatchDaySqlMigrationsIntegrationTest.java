package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("批次日相关数据库迁移 - 校验空库全量迁移、V1 后补齐 V2 与重复执行幂等性")
class BatchDaySqlMigrationsIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("batch_day_sql_guard");

  @Test
  @DisplayName("空库全量迁移后批次日实例表,业务日历新增列与乐观锁列齐备且唯一约束生效")
  void emptyDb_migration_createsBatchDayInstanceAndBusinessCalendarColumns() {
    final String insertBatchDayInstanceSql = """
            insert into batch.batch_day_instance(
              tenant_id, calendar_code, biz_date, day_status
            ) values ('t1', 'CAL', date '2026-03-27', 'NOT_REAL')
            """;

    resetDb();

    flywayConfiguration().load().migrate();

    JdbcTemplate jdbc = jdbc();
    try {
      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min"))
          .isTrue();

      assertThat(
              uniqueConstraintExists(jdbc, "batch", "batch_day_instance", "uk_batch_day_instance"))
          .isTrue();
      // V32：乐观锁 version 列
      assertThat(columnExists(jdbc, "batch", "job_instance", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_partition", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_task", "version")).isTrue();

      // sanity check：day_status check constraint 至少可用（非法值应失败）
      try {
        jdbc.update(insertBatchDayInstanceSql);
        assertThat(true)
            .as("invalid day_status should fail by check constraint")
            .isFalse();
      } catch (Exception expected) {
        // ok: constraint violation
      }
    } catch (Exception expected) {
      // ok: 期待约束异常
    } finally {
      closeJdbc(jdbc);
    }
  }

  @Test
  @DisplayName("只应用 V1 时批次日结构缺失,补跑 V2 后齐备且重复迁移不追加已应用记录")
  void schemaOnlyThenFullMigrate_createsBatchDayStructureAndIsIdempotent() {
    resetDb();

    // 现行链只有 V1（模式边界）和 V2（完整结构）。先停在 V1，确认批次日对象尚未出现。
    Flyway configured = flywayConfiguration().target("1").load();
    configured.migrate();

    JdbcTemplate jdbc = jdbc();
    try {
      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time"))
          .isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min"))
          .isFalse();

      // 再应用到 V2，补齐现行批次日结构。
      flywayConfiguration().load().migrate();

      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "job_instance", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_partition", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_task", "version")).isTrue();

      // 幂等性：重复 migrate，不应改变 flyway_schema_history 的 applied 行数
      Flyway fullFlyway = flywayConfiguration().load();
      long historyBefore = flywayHistoryCount(jdbc);
      fullFlyway.migrate();
      long historyAfter = flywayHistoryCount(jdbc);
      assertThat(historyAfter).isEqualTo(historyBefore);
    } finally {
      closeJdbc(jdbc);
    }
  }

  @Test
  @DisplayName("迁移连续执行两次时第二次得到的已应用版本集合与首次一致")
  void flywayMigrate_isIdempotent_secondRunSameAppliedSet() {
    resetDb();

    Flyway flyway = flywayConfiguration().load();

    flyway.migrate();
    JdbcTemplate jdbc = jdbc();
    try {
      assertThat(uniqueConstraintExists(jdbc, "batch", "shedlock", "pk_shedlock"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "shedlock", "lock_until")).isTrue();
    } finally {
      closeJdbc(jdbc);
    }
    List<String> applied1 = Arrays.stream(flyway.info().applied())
        .filter(Objects::nonNull)
        .map(MigrationInfo::getVersion)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();

    flyway.migrate();
    List<String> applied2 = Arrays.stream(flyway.info().applied())
        .filter(Objects::nonNull)
        .map(MigrationInfo::getVersion)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();

    assertThat(applied2).containsExactlyInAnyOrderElementsOf(applied1);
  }

  private static JdbcTemplate jdbc() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    return new JdbcTemplate(dataSource);
  }

  private static void closeJdbc(JdbcTemplate jdbc) {
    if (jdbc == null) {
      return;
    }
    // JdbcTemplate 没有直接暴露 destroy，这里通过底层 DataSource 释放连接
    if (jdbc.getDataSource() instanceof SingleConnectionDataSource s) {
      s.destroy();
    }
  }

  private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
    final String selectCountSql = """
            select count(*)
            from information_schema.tables
            where table_schema = ?
              and table_name = ?
            """;

    Long cnt = jdbc.queryForObject(selectCountSql, Long.class, schema, table);
    return cnt != null && cnt > 0;
  }

  private static boolean columnExists(
      JdbcTemplate jdbc, String schema, String table, String column) {
    final String selectCountSql = """
            select count(*)
            from information_schema.columns
            where table_schema = ?
              and table_name = ?
              and column_name = ?
            """;

    Long cnt = jdbc.queryForObject(selectCountSql, Long.class, schema, table, column);
    return cnt != null && cnt > 0;
  }

  private static boolean uniqueConstraintExists(
      JdbcTemplate jdbc, String schema, String table, String constraintName) {
    final String selectCountSql = """
            select count(*)
            from information_schema.table_constraints tc
            where tc.table_schema = ?
              and tc.table_name = ?
              and tc.constraint_name = ?
              and tc.constraint_type in ('UNIQUE', 'PRIMARY KEY')
            """;

    Long cnt = jdbc.queryForObject(selectCountSql, Long.class, schema, table, constraintName);
    return cnt != null && cnt > 0;
  }

  private static void resetDb() {
    JdbcTemplate jdbc = jdbc();
    try {
      // V2 创建 archive，但运行时 Flyway 只管理 batch 与 quartz；清理时单独丢掉 archive。
      jdbc.execute("DROP SCHEMA IF EXISTS archive CASCADE");
    } finally {
      closeJdbc(jdbc);
    }
    Flyway resetFlyway = flywayConfiguration().cleanDisabled(false).load();
    resetFlyway.clean();
  }

  private static FluentConfiguration flywayConfiguration() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }

  private static long flywayHistoryCount(JdbcTemplate jdbc) {
    final String selectCountSql = """
        select count(*) from batch.flyway_schema_history
    """;

    return jdbc.queryForObject(selectCountSql, Long.class);
  }
}
