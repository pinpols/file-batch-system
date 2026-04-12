package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class BatchDaySqlMigrationsIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("batch_day_sql_guard")
          .withUsername("batch_user")
          .withPassword("batch_pass_123");

  @Test
  void emptyDb_migration_createsBatchDayInstanceAndBusinessCalendarColumns() {
    resetDb();

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    JdbcTemplate jdbc = jdbc();
    try {
      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min")).isTrue();

      assertThat(
              uniqueConstraintExists(jdbc, "batch", "batch_day_instance", "uk_batch_day_instance"))
          .isTrue();
      // V32：乐观锁 version 列
      assertThat(columnExists(jdbc, "batch", "job_instance", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_partition", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_task", "version")).isTrue();

      // sanity check：day_status check constraint 至少可用（非法值应失败）
      try {
        jdbc.update(
            """
            insert into batch.batch_day_instance(
              tenant_id, calendar_code, biz_date, day_status
            ) values ('t1', 'CAL', date '2026-03-27', 'NOT_REAL')
            """);
        assertThat(true).as("invalid day_status should fail by check constraint").isFalse();
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
  void existingDb_upgradeFromBeforeV31_appliesV31AndIsIdempotent() {
    resetDb();

    // 先模拟已有库：只跑到 V29（target 设成 30 时等价于“<=29”，不要求 V30 脚本存在）
    Flyway configured =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("batch", "quartz")
            .defaultSchema("batch")
            .locations("classpath:db/migration")
            // 飞行目标必须存在真实迁移版本；用 V29 作为 V31 之前的“已升级到尽可能多”
            .target("29")
            .load();
    configured.migrate();

    JdbcTemplate jdbc = jdbc();
    try {
      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time")).isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isFalse();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min")).isFalse();

      // 再全量升级到最新版（补齐 V31/V32...）
      Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .schemas("batch", "quartz")
          .defaultSchema("batch")
          .locations("classpath:db/migration")
          .load()
          .migrate();

      assertThat(tableExists(jdbc, "batch", "batch_day_instance")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "cutoff_time")).isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "late_arrival_tolerance_min"))
          .isTrue();
      assertThat(columnExists(jdbc, "batch", "business_calendar", "sla_offset_min")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_instance", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_partition", "version")).isTrue();
      assertThat(columnExists(jdbc, "batch", "job_task", "version")).isTrue();

      // 幂等性：重复 migrate，不应改变 flyway_schema_history 的 applied 行数
      Flyway fullFlyway =
          Flyway.configure()
              .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
              .schemas("batch", "quartz")
              .defaultSchema("batch")
              .locations("classpath:db/migration")
              .load();
      long historyBefore = flywayHistoryCount(jdbc);
      fullFlyway.migrate();
      long historyAfter = flywayHistoryCount(jdbc);
      assertThat(historyAfter).isEqualTo(historyBefore);
    } finally {
      closeJdbc(jdbc);
    }
  }

  @Test
  void flywayMigrate_isIdempotent_secondRunSameAppliedSet() {
    resetDb();

    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("batch", "quartz")
            .defaultSchema("batch")
            .locations("classpath:db/migration")
            .load();

    flyway.migrate();
    List<String> applied1 =
        Arrays.stream(flyway.info().applied())
            .filter(Objects::nonNull)
            .map(MigrationInfo::getVersion)
            .filter(Objects::nonNull)
            .map(Object::toString)
            .toList();

    flyway.migrate();
    List<String> applied2 =
        Arrays.stream(flyway.info().applied())
            .filter(Objects::nonNull)
            .map(MigrationInfo::getVersion)
            .filter(Objects::nonNull)
            .map(Object::toString)
            .toList();

    assertThat(applied2).containsExactlyInAnyOrderElementsOf(applied1);
  }

  private static JdbcTemplate jdbc() {
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
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
    Long cnt =
        jdbc.queryForObject(
            """
            select count(*)
            from information_schema.tables
            where table_schema = ?
              and table_name = ?
            """,
            Long.class,
            schema,
            table);
    return cnt != null && cnt > 0;
  }

  private static boolean columnExists(
      JdbcTemplate jdbc, String schema, String table, String column) {
    Long cnt =
        jdbc.queryForObject(
            """
            select count(*)
            from information_schema.columns
            where table_schema = ?
              and table_name = ?
              and column_name = ?
            """,
            Long.class,
            schema,
            table,
            column);
    return cnt != null && cnt > 0;
  }

  private static boolean uniqueConstraintExists(
      JdbcTemplate jdbc, String schema, String table, String constraintName) {
    Long cnt =
        jdbc.queryForObject(
            """
            select count(*)
            from information_schema.table_constraints tc
            where tc.table_schema = ?
              and tc.table_name = ?
              and tc.constraint_name = ?
              and tc.constraint_type in ('UNIQUE', 'PRIMARY KEY')
            """,
            Long.class,
            schema,
            table,
            constraintName);
    return cnt != null && cnt > 0;
  }

  private static void resetDb() {
    Flyway resetFlyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("batch", "quartz")
            .defaultSchema("batch")
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    resetFlyway.clean();
  }

  private static long flywayHistoryCount(JdbcTemplate jdbc) {
    return jdbc.queryForObject("select count(*) from batch.flyway_schema_history", Long.class);
  }
}
