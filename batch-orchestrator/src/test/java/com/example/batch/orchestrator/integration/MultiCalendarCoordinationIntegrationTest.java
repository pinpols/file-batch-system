package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.infrastructure.scheduler.BatchDayOpenScheduler;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-023 Stage 5-6 — 多日历联动端到端 IT。
 *
 * <p>priority-scope §ADR-023 ✅ 清单的硬质量门：跨日历依赖 / disaster_day_override / cutoff_schedule 三个 backend
 * 能力在真实 PG 上联动跑通，不只是 unit-mock 覆盖。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>calendar_dependency: HK 等 CN SETTLED — 当 CN.day_status≠SETTLED 时 HK 不开；CN 标 SETTLED 后 HK 可开
 *   <li>disaster_day_override: SKIP override 触发后 batch_day_instance 状态为 SKIPPED / DEFERRED
 *   <li>cutoff_schedule JSONB 字段成功落库可读
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MultiCalendarCoordinationIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ = LocalDate.of(2026, 5, 7);

  @Autowired private BatchDayOpenScheduler scheduler;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedCleanCalendars() {
    jdbcTemplate.update("delete from batch.calendar_dependency where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.disaster_day_override where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.batch_day_instance where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.business_calendar where tenant_id = ?", TENANT);
  }

  @Test
  void hkBlocksOnCnUntilSettled() {
    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Hong_Kong");
    // HK 依赖 CN SETTLED
    jdbcTemplate.update(
        "insert into batch.calendar_dependency"
            + " (tenant_id, upstream_code, downstream_code, rule, enabled)"
            + " values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)",
        TENANT);

    // 第一轮：CN 还没 SETTLED → HK 应被 deferred / 不开
    Instant now = BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("UTC")).toInstant();
    scheduler.openDueBatchDays(now);
    Map<String, Object> hkRow = selectFirstBatchDay("CAL_HK");
    Map<String, Object> cnRow = selectFirstBatchDay("CAL_CN");
    // CN 应该已开（IN_FLIGHT / OPEN），HK 应该未开（calendar_dependency 拦截）
    assertThat(cnRow).isNotNull();
    assertThat(cnRow.get("day_status")).isIn("OPEN", "IN_FLIGHT");
    if (hkRow != null) {
      assertThat(hkRow.get("day_status")).isNotEqualTo("IN_FLIGHT");
    }

    // 模拟 CN 进 SETTLED
    jdbcTemplate.update(
        "update batch.batch_day_instance"
            + " set day_status = 'SETTLED', settled_at = current_timestamp"
            + " where tenant_id = ? and calendar_code = 'CAL_CN'",
        TENANT);

    // 第二轮：CN SETTLED 后 HK 应能开
    scheduler.openDueBatchDays(now);
    Map<String, Object> hkRowAfter = selectFirstBatchDay("CAL_HK");
    assertThat(hkRowAfter).isNotNull();
    assertThat(hkRowAfter.get("day_status")).isIn("OPEN", "IN_FLIGHT");
  }

  @Test
  void disasterSkipOverrideShortCircuitsOpen() {
    insertCalendar("CAL_US", "America/New_York");
    // NY EDT cutoff=22:00 → 用 NY 本地 22:01 of BIZ 让 scheduler 算出 bizDate=BIZ
    Instant now = BIZ.atTime(22, 1).atZone(java.time.ZoneId.of("America/New_York")).toInstant();
    // disaster_day_override.effective_at 默认 = current_timestamp(墙钟今天),
    // 而测试 now = BIZ(可能是几天前)。selectActiveByCalendarBizDate 的
    // "effective_at <= now" 过滤会把这条 override 过滤掉。显式把 effective_at
    // 设为 now - 1 小时,保证 mapper 能命中。
    jdbcTemplate.update(
        "insert into batch.disaster_day_override"
            + " (tenant_id, calendar_code, biz_date, action, reason, approved_by,"
            + "  approved_at, effective_at, ttl_until)"
            + " values (?, 'CAL_US', ?, 'SKIP', 'hurricane', 'ops-1', ?, ?, ?)",
        TENANT,
        BIZ,
        java.sql.Timestamp.from(now.minusSeconds(3600)),
        java.sql.Timestamp.from(now.minusSeconds(3600)),
        java.sql.Timestamp.from(now.plusSeconds(86400)));
    scheduler.openDueBatchDays(now);
    Map<String, Object> usRow = selectFirstBatchDay("CAL_US");

    // 灾难 SKIP 触发后，batch_day_instance 进 SKIPPED 状态（看 BatchDayOpenScheduler.handleDisasterOverride）
    assertThat(usRow).isNotNull();
    assertThat(usRow.get("day_status")).isIn("SKIPPED", "DEFERRED");
  }

  @Test
  void cutoffScheduleJsonbPersists() {
    String cutoffJson = "{\"default\":\"22:00\",\"weekdayPattern\":{\"THURSDAY\":\"12:00\"}}";
    jdbcTemplate.update(
        "insert into batch.business_calendar"
            + " (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,"
            + "  enabled, cutoff_schedule)"
            + " values (?, 'CAL_JP', 'JP', 'Asia/Tokyo', '22:00', true, ?::jsonb)",
        TENANT,
        cutoffJson);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select calendar_code, cutoff_schedule::text as cutoff_schedule"
                + " from batch.business_calendar"
                + " where tenant_id = ? and calendar_code = 'CAL_JP'",
            TENANT);

    assertThat(row.get("cutoff_schedule").toString())
        .contains("default")
        .contains("22:00")
        .contains("THURSDAY")
        .contains("12:00");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void insertCalendar(String code, String timezone) {
    jdbcTemplate.update(
        "insert into batch.business_calendar"
            + " (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, enabled)"
            + " values (?, ?, ?, ?, '22:00', true)",
        TENANT,
        code,
        code,
        timezone);
  }

  private Map<String, Object> selectFirstBatchDay(String calendarCode) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select * from batch.batch_day_instance where tenant_id = ? and calendar_code = ?"
                + " order by biz_date desc, id desc limit 1",
            TENANT,
            calendarCode);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
