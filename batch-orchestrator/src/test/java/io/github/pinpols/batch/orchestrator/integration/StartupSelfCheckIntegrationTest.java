package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.health.BatchStartupSelfCheck;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：编排器启动 + Flyway 迁移 schema 校验链路。
 *
 * <p>验证 Spring 上下文启动后（通过 {@link org.flywaydb.core.Flyway} 触发 Flyway 迁移）， 以下条件均满足：
 *
 * <ul>
 *   <li>{@code batch} 和 {@code quartz} schema 存在
 *   <li>{@code batch.batch_day_instance} 表存在（V31）
 *   <li>V31 添加的 {@code batch.business_calendar} 列存在
 *   <li>所有 Quartz 表在 {@code quartz} schema 中存在
 *   <li>{@link BatchStartupSelfCheck} Bean 存在于上下文中（即自检已装配）
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("编排器启动自检 - 校验迁移后批处理域与调度域结构齐备且自检组件已装配")
class StartupSelfCheckIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private BatchStartupSelfCheck startupSelfCheck;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("应用上下文启动完成后启动自检组件已装配且非空")
  void shouldExposeSelfCheckComponent_whenContextStarts() {
    assertThat(startupSelfCheck).isNotNull();
  }

  @Test
  @DisplayName("迁移执行完成后批处理域结构存在且仅有一个")
  void shouldCreateBatchSchema_whenMigrationRuns() {
    Long cnt = jdbcTemplate.queryForObject(
        "select count(*) from information_schema.schemata where schema_name = 'batch'", Long.class);
    assertThat(cnt).isEqualTo(1L);
  }

  @Test
  @DisplayName("迁移执行完成后定时调度域结构存在且仅有一个")
  void shouldCreateSchedulerSchema_whenMigrationRuns() {
    Long cnt = jdbcTemplate.queryForObject(
        "select count(*) from information_schema.schemata where schema_name = 'quartz'",
        Long.class);
    assertThat(cnt).isEqualTo(1L);
  }

  @Test
  @DisplayName("迁移执行完成后批次日实例表存在且仅有一个")
  void shouldCreateBatchDayInstanceTable_whenMigrationRuns() {
    Long cnt = jdbcTemplate.queryForObject("""
            select count(*) from information_schema.tables
            where table_schema = 'batch' and table_name = 'batch_day_instance'
            """, Long.class);
    assertThat(cnt).isEqualTo(1L);
  }

  @Test
  @DisplayName("迁移执行完成后业务日历的截止时刻,迟到容忍与时限偏移列均存在")
  void shouldAddCalendarTimingColumns_whenMigrationRuns() {
    for (String column :
        new String[] {"cutoff_time", "late_arrival_tolerance_min", "sla_offset_min"}) {
      Long cnt = jdbcTemplate.queryForObject("""
              select count(*) from information_schema.columns
              where table_schema = 'batch'
                and table_name = 'business_calendar'
                and column_name = ?
              """, Long.class, column);
      assertThat(cnt).as("column business_calendar.%s should exist", column).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("迁移执行完成后定时调度域的全部调度表均存在")
  void shouldCreateAllSchedulerTables_whenMigrationRuns() {
    for (String table : new String[] {
      "qrtz_job_details", "qrtz_triggers", "qrtz_simple_triggers",
      "qrtz_cron_triggers", "qrtz_simprop_triggers", "qrtz_blob_triggers",
      "qrtz_calendars", "qrtz_paused_trigger_grps", "qrtz_fired_triggers",
      "qrtz_scheduler_state", "qrtz_locks"
    }) {
      Long cnt = jdbcTemplate.queryForObject("""
              select count(*) from information_schema.tables
              where table_schema = 'quartz' and table_name = ?
              """, Long.class, table);
      assertThat(cnt).as("quartz table %s should exist", table).isEqualTo(1L);
    }
  }
}
