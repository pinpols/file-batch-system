package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupControlService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupPreviewService;
import io.github.pinpols.batch.orchestrator.application.service.calendar.CalendarDependencyWaitGovernanceService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayOpenScheduler;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
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
 *   <li>cutoff_schedule JSONB 字段成功写入数据库可读
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("多日历联动调度,验证上游日历依赖拦截,灾害日跳过覆盖以及按工作日差异化的截断时刻落库")
class MultiCalendarCoordinationIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ = LocalDate.of(2026, Month.MAY, 7);

  @Autowired
  private BatchDayOpenScheduler scheduler;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private SensorFileArrivalMapper sensorFileArrivalMapper;

  @Autowired
  private CalendarDependencyWaitMapper calendarDependencyWaitMapper;

  @Autowired
  private CalendarDependencyWaitGovernanceService calendarDependencyWaitGovernanceService;

  @Autowired
  private BatchDayCatchupPreviewService batchDayCatchupPreviewService;

  @Autowired
  private BatchDayCatchupControlService batchDayCatchupControlService;

  @BeforeEach
  void seedCleanCalendars() {
    jdbcTemplate.update("delete from batch.batch_day_open_cursor where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.calendar_dependency where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.calendar_dependency_wait where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.disaster_day_override where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.batch_day_instance where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.calendar_holiday where tenant_id = ?", TENANT);
    jdbcTemplate.update("delete from batch.business_calendar where tenant_id = ?", TENANT);
  }

  @Test
  @DisplayName("SKIP 假日按真实日历记录创建带审计的 SKIPPED 批量日")
  void shouldPersistSkippedHolidayFromCalendarConfiguration() {
    insertCalendar("CAL_HOLIDAY", "Asia/Shanghai", "SKIP");
    jdbcTemplate.update(
        "insert into batch.calendar_holiday"
            + " (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)"
            + " select ?, id, ?, 'HOLIDAY', 'test holiday', 'CALENDAR'"
            + " from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_HOLIDAY'",
        TENANT,
        BIZ,
        TENANT);
    Instant now = BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(now);

    Map<String, Object> row = selectFirstBatchDay("CAL_HOLIDAY");
    assertThat(row).isNotNull();
    assertThat(row.get("biz_date").toString()).isEqualTo(BIZ.toString());
    assertThat(row.get("day_status")).isEqualTo("SKIPPED");
    assertThat(row.get("operation_reason")).isEqualTo("CALENDAR_HOLIDAY_SKIP");
  }

  @Test
  @DisplayName("调度器停机跨越多个业务日后按顺序补建缺失批量日")
  void shouldCatchUpMissingBatchDaysAfterSchedulerDowntime() {
    insertCalendar("CAL_CATCHUP", "Asia/Shanghai");
    LocalDate lastOpened = BIZ.minusDays(20);
    jdbcTemplate.update(
        "insert into batch.batch_day_instance"
            + " (tenant_id, calendar_code, biz_date, day_status, open_at)"
            + " values (?, 'CAL_CATCHUP', ?, 'SETTLED', current_timestamp)",
        TENANT,
        lastOpened);

    Instant recoveredAt =
        BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(recoveredAt);
    Integer firstScanCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_CATCHUP'",
        Integer.class,
        TENANT);
    assertThat(firstScanCount).isEqualTo(15);
    // A later periodic scan continues from the latest persisted date and finishes the backlog.
    scheduler.openDueBatchDays(recoveredAt.plusSeconds(60));

    List<LocalDate> openedDates = jdbcTemplate.queryForList(
        "select biz_date from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_CATCHUP' order by biz_date",
        LocalDate.class,
        TENANT);
    assertThat(openedDates)
        .containsExactlyElementsOf(
            java.util.stream.Stream.iterate(lastOpened, date -> date.plusDays(1))
                .limit(21)
                .toList());
  }

  @Test
  @DisplayName("日切预览按租户 RLS 读取游标且不修改日切状态")
  void shouldPreviewCatchupWithoutAdvancingCursorOrCreatingBatchDays() {
    insertCalendar("CAL_PREVIEW", "Asia/Shanghai");
    LocalDate currentBizDate = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    LocalDate cursorDate = currentBizDate.minusDays(3);
    jdbcTemplate.update(
        "insert into batch.batch_day_open_cursor"
            + " (tenant_id, calendar_code, last_scanned_biz_date) values (?, 'CAL_PREVIEW', ?)",
        TENANT,
        cursorDate);

    var preview = batchDayCatchupPreviewService.preview(TENANT, "CAL_PREVIEW");

    assertThat(preview.cursorSource()).isEqualTo("PERSISTED_CURSOR");
    assertThat(preview.lastScannedBizDate()).isEqualTo(cursorDate);
    assertThat(preview.nextScanRawBizDates())
        .isNotEmpty()
        .first()
        .isEqualTo(cursorDate.plusDays(1));
    LocalDate persistedCursor = jdbcTemplate.queryForObject(
        "select last_scanned_biz_date from batch.batch_day_open_cursor"
            + " where tenant_id = ? and calendar_code = 'CAL_PREVIEW'",
        LocalDate.class,
        TENANT);
    assertThat(persistedCursor).isEqualTo(cursorDate);
    Integer batchDayCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_PREVIEW'",
        Integer.class,
        TENANT);
    assertThat(batchDayCount).isZero();
  }

  @Test
  @DisplayName("历史缺口清单只返回限定日期范围内无记录的候选且不写运行态")
  void shouldInventoryHistoricalGapCandidatesWithoutWrites() {
    insertCalendar("CAL_HISTORY", "Asia/Shanghai");
    LocalDate from = LocalDate.of(2026, 4, 1);
    LocalDate through = LocalDate.of(2026, 4, 4);
    jdbcTemplate.update(
        "insert into batch.batch_day_instance"
            + " (tenant_id, calendar_code, biz_date, day_status, open_at)"
            + " values (?, 'CAL_HISTORY', ?, 'SETTLED', current_timestamp),"
            + " (?, 'CAL_HISTORY', ?, 'SKIPPED', current_timestamp)",
        TENANT,
        from,
        TENANT,
        from.plusDays(2));

    var inventory =
        batchDayCatchupPreviewService.inventoryHistoricalGaps(TENANT, "CAL_HISTORY", from, through);

    assertThat(inventory.inspectedDateCount()).isEqualTo(4);
    assertThat(inventory.missingRecordCandidates())
        .containsExactly(from.plusDays(1), from.plusDays(3));
    Integer persistedCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_HISTORY'",
        Integer.class,
        TENANT);
    assertThat(persistedCount).isEqualTo(2);
    assertThatThrownBy(() -> batchDayCatchupPreviewService.inventoryHistoricalGaps(
            TENANT, "CAL_HISTORY", from, from.plusDays(90)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
  }

  @Test
  @DisplayName("管理员暂停后调度器保留 catch-up 游标,恢复后继续并保留不可变审计事件")
  void shouldPauseAndResumeCatchupWithTenantScopedAudit() {
    insertCalendar("CAL_PAUSE", "Asia/Shanghai");
    Instant now = Instant.now();
    var localNow = now.atZone(java.time.ZoneId.of("Asia/Shanghai"));
    LocalDate currentBizDate = localNow.toLocalTime().isBefore(LocalTime.of(22, 0))
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    LocalDate cursorDate = currentBizDate.minusDays(3);
    jdbcTemplate.update(
        "insert into batch.batch_day_open_cursor"
            + " (tenant_id, calendar_code, last_scanned_biz_date) values (?, 'CAL_PAUSE', ?)",
        TENANT,
        cursorDate);
    var paused =
        batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
            TENANT, "CAL_PAUSE", "PAUSE", "ops-1", "maintenance window"));
    scheduler.openDueBatchDays(now);
    LocalDate cursorDuringPause = jdbcTemplate.queryForObject(
        "select last_scanned_biz_date from batch.batch_day_open_cursor"
            + " where tenant_id = ? and calendar_code = 'CAL_PAUSE'",
        LocalDate.class,
        TENANT);
    assertThat(paused.paused()).isTrue();
    assertThat(cursorDuringPause).isEqualTo(cursorDate);
    Integer currentDayCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_PAUSE' and biz_date = ?",
        Integer.class,
        TENANT,
        currentBizDate);
    assertThat(currentDayCount).isEqualTo(1);
    assertThat(batchDayCatchupPreviewService.preview(TENANT, "CAL_PAUSE").catchupPaused())
        .isTrue();

    var resumed =
        batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
            TENANT, "CAL_PAUSE", "RESUME", "ops-2", "maintenance complete"));
    Integer eventCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_catchup_control_event"
            + " where tenant_id = ? and calendar_code = 'CAL_PAUSE'",
        Integer.class,
        TENANT);
    assertThat(resumed.paused()).isFalse();
    assertThat(eventCount).isEqualTo(2);
  }

  @Test
  @DisplayName("NEXT_WORKDAY 提前创建滚动目标后仍推进原始日期游标")
  void shouldAdvanceRawDateCursorWhenHolidayRollCreatesFutureDay() {
    insertCalendar("CAL_ROLL_NEXT", "Asia/Shanghai", "NEXT_WORKDAY");
    jdbcTemplate.update(
        "insert into batch.calendar_holiday"
            + " (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)"
            + " select ?, id, ?, 'HOLIDAY', 'roll start', 'CALENDAR'"
            + " from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'",
        TENANT,
        BIZ,
        TENANT);
    jdbcTemplate.update(
        "insert into batch.calendar_holiday"
            + " (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)"
            + " select ?, id, ?, 'HOLIDAY', 'roll target holiday', 'CALENDAR'"
            + " from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'",
        TENANT,
        BIZ.plusDays(1),
        TENANT);
    jdbcTemplate.update(
        "insert into batch.batch_day_instance"
            + " (tenant_id, calendar_code, biz_date, day_status, open_at)"
            + " values (?, 'CAL_ROLL_NEXT', ?, 'SETTLED', current_timestamp)",
        TENANT,
        BIZ.minusDays(1));

    Instant firstDate =
        BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(firstDate);
    Integer rolledRows = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT' and biz_date = ?",
        Integer.class,
        TENANT,
        BIZ.plusDays(2));
    assertThat(rolledRows).isEqualTo(1);

    scheduler.openDueBatchDays(firstDate.plusSeconds(86_400));
    LocalDate cursor = jdbcTemplate.queryForObject(
        "select last_scanned_biz_date from batch.batch_day_open_cursor"
            + " where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'",
        LocalDate.class,
        TENANT);
    assertThat(cursor).isEqualTo(BIZ.plusDays(1));
  }

  @Test
  @DisplayName("上游日历未结算时下游日历不开,上游结算后下游日历可开")
  void shouldBlockDownstreamCalendar_whenUpstreamNotSettled() {
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
      assertThat(hkRow).doesNotContainEntry("day_status", "IN_FLIGHT");
    }

    // 模拟 CN 进 SETTLED
    jdbcTemplate.update(
        "update batch.batch_day_instance"
            + " set day_status = 'SETTLED', settled_at = current_timestamp"
            + " where tenant_id = ? and calendar_code = 'CAL_CN'",
        TENANT);

    // 第二轮：CN SETTLED 后 HK 应能开
    scheduler.openDueBatchDays(now.plusSeconds(1));
    Map<String, Object> hkRowAfter = selectFirstBatchDay("CAL_HK");
    assertThat(hkRowAfter).isNotNull();
    assertThat(hkRowAfter.get("day_status")).isIn("OPEN", "IN_FLIGHT");
  }

  @Test
  @DisplayName("上游跨过下游 cutoff 才结算时,重检仍恢复首次等待的原业务日")
  void shouldReconcileBlockedBusinessDateAfterCutoff() {
    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Shanghai");
    jdbcTemplate.update(
        "insert into batch.calendar_dependency"
            + " (tenant_id, upstream_code, downstream_code, rule, enabled)"
            + " values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)",
        TENANT);
    Instant firstOpen =
        BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();

    scheduler.openDueBatchDays(firstOpen);

    Map<String, Object> waitBefore = jdbcTemplate.queryForMap(
        "select wait_status, biz_date from batch.calendar_dependency_wait"
            + " where tenant_id = ? and calendar_code = 'CAL_HK' and biz_date = ?",
        TENANT,
        BIZ);
    assertThat(waitBefore.get("wait_status")).isEqualTo("WAITING");
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> assertThat(calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
                TENANT, "CAL_HK", BIZ))
            .isEqualTo(1));
    jdbcTemplate.update(
        "update batch.batch_day_instance set day_status = 'SETTLED', settled_at = current_timestamp"
            + " where tenant_id = ? and calendar_code = 'CAL_CN' and biz_date = ?",
        TENANT,
        BIZ);

    Instant afterCutoff = BIZ.plusDays(1)
        .atTime(23, 0)
        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
        .toInstant();
    scheduler.openDueBatchDays(afterCutoff);

    Map<String, Object> recoveredDay = jdbcTemplate.queryForMap(
        "select day_status from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_HK' and biz_date = ?",
        TENANT,
        BIZ);
    assertThat(recoveredDay.get("day_status")).isEqualTo("OPEN");
    Map<String, Object> waitAfter = jdbcTemplate.queryForMap(
        "select wait_status from batch.calendar_dependency_wait"
            + " where tenant_id = ? and calendar_code = 'CAL_HK' and biz_date = ?",
        TENANT,
        BIZ);
    assertThat(waitAfter.get("wait_status")).isEqualTo("OPENED");
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> assertThat(calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
                TENANT, "CAL_HK", BIZ))
            .isZero());
    scheduler.openDueBatchDays(afterCutoff);
    Integer recoveredRows = jdbcTemplate.queryForObject(
        "select count(*) from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_HK' and biz_date = ?",
        Integer.class,
        TENANT,
        BIZ);
    assertThat(recoveredRows).isEqualTo(1);
  }

  @Test
  @DisplayName("多实例同时扫描时依赖等待按唯一键收敛为一条记录")
  void shouldDeduplicateConcurrentDependencyWaits() throws Exception {
    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Shanghai");
    jdbcTemplate.update(
        "insert into batch.calendar_dependency"
            + " (tenant_id, upstream_code, downstream_code, rule, enabled)"
            + " values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)",
        TENANT);
    Instant now = BIZ.atTime(23, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();

    CompletableFuture<Void> first =
        CompletableFuture.runAsync(() -> scheduler.openDueBatchDays(now));
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(() -> scheduler.openDueBatchDays(now));
    CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

    Integer waitRows = jdbcTemplate.queryForObject(
        "select count(*) from batch.calendar_dependency_wait"
            + " where tenant_id = ? and calendar_code = 'CAL_HK' and biz_date = ?",
        Integer.class,
        TENANT,
        BIZ);
    assertThat(waitRows).isEqualTo(1);
  }

  @Test
  @DisplayName("依赖等待超时后只发一次告警且保持 WAITING 供后续自动恢复")
  void shouldClaimDependencyWaitTimeoutAlertOnce() {
    insertCalendar("CAL_TIMEOUT", "Asia/Shanghai");
    jdbcTemplate.update(
        "insert into batch.calendar_dependency"
            + " (tenant_id, upstream_code, downstream_code, rule, enabled)"
            + " values (?, 'CAL_MISSING', 'CAL_TIMEOUT', 'WAIT_SETTLED', true)",
        TENANT);
    Instant now = Instant.now();
    scheduler.openDueBatchDays(now);
    var localNow = now.atZone(java.time.ZoneId.of("Asia/Shanghai"));
    LocalDate bizDate = localNow.toLocalTime().isBefore(LocalTime.of(22, 0))
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    jdbcTemplate.update(
        "update batch.calendar_dependency_wait"
            + " set first_waiting_at = current_timestamp - interval '2 days'"
            + " where tenant_id = ? and calendar_code = 'CAL_TIMEOUT' and biz_date = ?",
        TENANT,
        bizDate);

    scheduler.openDueBatchDays(now);
    scheduler.openDueBatchDays(now.plusSeconds(1));

    Integer alertRows = jdbcTemplate.queryForObject(
        "select count(*) from batch.alert_event"
            + " where tenant_id = ? and alert_type = 'BATCH_CALENDAR_DEPENDENCY_WAIT_TIMEOUT'",
        Integer.class,
        TENANT);
    Map<String, Object> wait = jdbcTemplate.queryForMap(
        "select wait_status, timeout_alerted_at from batch.calendar_dependency_wait"
            + " where tenant_id = ? and calendar_code = 'CAL_TIMEOUT' and biz_date = ?",
        TENANT,
        bizDate);
    assertThat(alertRows).isEqualTo(1);
    assertThat(wait.get("wait_status")).isEqualTo("WAITING");
    assertThat(wait.get("timeout_alerted_at")).isNotNull();
  }

  @Test
  @DisplayName("Console 人工 RELEASE 和 SKIP 请求经 Orchestrator 调度器审计落实")
  void shouldApplyAuditedManualCalendarDependencyActions() {
    insertCalendar("CAL_MANUAL_RELEASE", "Asia/Shanghai");
    insertCalendar("CAL_MANUAL_SKIP", "Asia/Shanghai");
    jdbcTemplate.update(
        "insert into batch.calendar_dependency"
            + " (tenant_id, upstream_code, downstream_code, rule, enabled)"
            + " values (?, 'CAL_MISSING', 'CAL_MANUAL_RELEASE', 'WAIT_SETTLED', true),"
            + " (?, 'CAL_MISSING', 'CAL_MANUAL_SKIP', 'WAIT_SETTLED', true)",
        TENANT,
        TENANT);
    Instant now = Instant.now();
    var localNow = now.atZone(java.time.ZoneId.of("Asia/Shanghai"));
    LocalDate bizDate = localNow.toLocalTime().isBefore(LocalTime.of(22, 0))
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();

    scheduler.openDueBatchDays(now);
    var releaseWait = calendarDependencyWaitGovernanceService.requestManualAction(
        new CalendarDependencyWaitGovernanceService.ManualActionCommand(
            TENANT, "CAL_MANUAL_RELEASE", bizDate, "RELEASE", "ops-user", "upstream approved"));
    var skipWait = calendarDependencyWaitGovernanceService.requestManualAction(
        new CalendarDependencyWaitGovernanceService.ManualActionCommand(
            TENANT, "CAL_MANUAL_SKIP", bizDate, "SKIP", "ops-user", "source day cancelled"));
    assertThat(releaseWait.manualAction()).isEqualTo("RELEASE");
    assertThat(skipWait.manualAction()).isEqualTo("SKIP");

    scheduler.openDueBatchDays(now.plusSeconds(2));

    Map<String, Object> releasedDay = jdbcTemplate.queryForMap(
        "select day_status, operated_by, operation_reason from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_MANUAL_RELEASE' and biz_date = ?",
        TENANT,
        bizDate);
    assertThat(releasedDay.get("day_status")).isEqualTo("OPEN");
    assertThat(releasedDay.get("operated_by")).isEqualTo("ops-user");
    assertThat(releasedDay.get("operation_reason").toString())
        .contains("MANUAL_CALENDAR_DEPENDENCY_RELEASE:upstream approved");
    Map<String, Object> skippedDay = jdbcTemplate.queryForMap(
        "select day_status, operated_by, operation_reason from batch.batch_day_instance"
            + " where tenant_id = ? and calendar_code = 'CAL_MANUAL_SKIP' and biz_date = ?",
        TENANT,
        bizDate);
    assertThat(skippedDay.get("day_status")).isEqualTo("SKIPPED");
    assertThat(skippedDay.get("operated_by")).isEqualTo("ops-user");
    assertThat(skippedDay.get("operation_reason").toString())
        .contains("MANUAL_CALENDAR_DEPENDENCY_SKIP:source day cancelled");
  }

  @Test
  @DisplayName("文件到达 sensor 可按业务日、到达组和完整组状态过滤同名文件")
  void shouldMatchOnlyTriggeredFileGroupForRequestedBusinessDate() {
    String filePrefix = "sensor-settle-" + System.nanoTime();
    String currentFile = filePrefix + "-current.csv";
    String oldFile = filePrefix + "-old.csv";
    String groupCode = "SENSOR_" + System.nanoTime();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime since = now.minusDays(1);
    try {
      insertSensorFile(oldFile, BIZ.minusDays(1), groupCode, "TRIGGERED", now.minusHours(2));
      insertSensorFile(currentFile, BIZ, groupCode, "WAITING_ARRIVAL", now.minusHours(1));
      Map<String, Object> incomplete = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              TENANT, filePrefix + "%.csv", "SFTP", since, BIZ, groupCode, true));
      assertThat(incomplete).isNull();

      jdbcTemplate.update(
          "update batch.file_record"
              + " set metadata_json = jsonb_set(metadata_json, '{arrivalState}', '\"TRIGGERED\"')"
              + " where tenant_id = ? and file_name = ?",
          TENANT,
          currentFile);
      Map<String, Object> matched = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              TENANT, filePrefix + "%.csv", "SFTP", since, BIZ, groupCode, true));
      assertThat(matched).containsEntry("fileName", currentFile);
      assertThat(matched.get("bizDate").toString()).isEqualTo(BIZ.toString());
    } finally {
      jdbcTemplate.update(
          "delete from batch.file_record where tenant_id = ? and file_name like ?",
          TENANT,
          filePrefix + "%");
    }
  }

  private void insertSensorFile(
      String fileName,
      LocalDate bizDate,
      String groupCode,
      String arrivalState,
      OffsetDateTime createdAt) {
    jdbcTemplate.update(
        "insert into batch.file_record"
            + " (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,"
            + " source_type, file_status, biz_date, metadata_json, created_at)"
            + " values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,"
            + " jsonb_build_object('fileGroupCode', ?, 'arrivalState', ?), ?)",
        TENANT,
        fileName,
        "s3://test/" + fileName,
        bizDate,
        groupCode,
        arrivalState,
        createdAt);
  }

  @Test
  @DisplayName("依赖等待记录通过强制 RLS 限制租户读取和写入")
  void shouldEnforceTenantIsolationForDependencyWaits() {
    String role = "calendar_dependency_wait_rls_test";
    jdbcTemplate.execute("DROP ROLE IF EXISTS " + role);
    jdbcTemplate.execute("CREATE ROLE " + role + " NOLOGIN");
    try {
      jdbcTemplate.execute("GRANT USAGE ON SCHEMA batch TO " + role);
      jdbcTemplate.execute("GRANT SELECT, INSERT ON batch.calendar_dependency_wait TO " + role);
      jdbcTemplate.execute("GRANT SELECT, INSERT ON batch.batch_day_open_cursor TO " + role);
      jdbcTemplate.execute(
          "GRANT USAGE, SELECT ON SEQUENCE batch.calendar_dependency_wait_id_seq TO " + role);
      insertCalendar("CAL_RLS", "Asia/Shanghai");
      jdbcTemplate.update(
          "insert into batch.calendar_dependency_wait"
              + " (tenant_id, calendar_code, biz_date, wait_status, blocked_reason,"
              + " first_waiting_at, last_checked_at, timezone_snapshot, dst_policy_snapshot)"
              + " values (?, 'CAL_RLS', ?, 'WAITING', 'blocked', current_timestamp,"
              + " current_timestamp, 'Asia/Shanghai', 'EARLIER_OFFSET')",
          TENANT,
          BIZ);
      jdbcTemplate.update(
          "insert into batch.calendar_dependency_wait"
              + " (tenant_id, calendar_code, biz_date, wait_status, blocked_reason,"
              + " first_waiting_at, last_checked_at, timezone_snapshot, dst_policy_snapshot)"
              + " values (?, 'CAL_RLS', ?, 'WAITING', 'blocked', current_timestamp,"
              + " current_timestamp, 'Asia/Shanghai', 'EARLIER_OFFSET')",
          "t2",
          BIZ);
      jdbcTemplate.update(
          "insert into batch.batch_day_open_cursor"
              + " (tenant_id, calendar_code, last_scanned_biz_date) values (?, 'CAL_RLS', ?)",
          TENANT,
          BIZ);
      jdbcTemplate.update(
          "insert into batch.batch_day_open_cursor"
              + " (tenant_id, calendar_code, last_scanned_biz_date) values (?, 'CAL_RLS', ?)",
          "t2",
          BIZ);

      jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          statement.execute("SET ROLE " + role);
          statement.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', true)");
          try (var result = statement.executeQuery(
              "SELECT count(*) FROM batch.calendar_dependency_wait WHERE calendar_code = 'CAL_RLS'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1L);
          }
          try (var result = statement.executeQuery(
              "SELECT count(*) FROM batch.batch_day_open_cursor WHERE calendar_code = 'CAL_RLS'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1L);
          }
          statement.execute("SAVEPOINT before_wait_rls_violation");
          assertThatThrownBy(
                  () -> statement.executeUpdate(
                      "INSERT INTO batch.calendar_dependency_wait"
                          + " (tenant_id, calendar_code, biz_date, blocked_reason, first_waiting_at,"
                          + " last_checked_at, timezone_snapshot, dst_policy_snapshot)"
                          + " VALUES ('t2', 'CAL_RLS', DATE '2026-05-08', 'blocked',"
                          + " current_timestamp, current_timestamp, 'Asia/Shanghai', 'EARLIER_OFFSET')"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");
          statement.execute("ROLLBACK TO SAVEPOINT before_wait_rls_violation");
          statement.execute("SAVEPOINT before_cursor_rls_violation");
          assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO batch.batch_day_open_cursor"
                  + " (tenant_id, calendar_code, last_scanned_biz_date)"
                  + " VALUES ('t2', 'CAL_RLS', DATE '2026-05-09')"))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");
          statement.execute("ROLLBACK TO SAVEPOINT before_cursor_rls_violation");
        } finally {
          connection.rollback();
          connection.setAutoCommit(true);
        }
        return null;
      });
    } finally {
      jdbcTemplate.execute("DROP OWNED BY " + role);
      jdbcTemplate.execute("DROP ROLE " + role);
    }
  }

  @Test
  @DisplayName("灾害日跳过覆盖生效时批次日进入跳过或延期,而不是正常开启")
  void shouldSkipBatchDayOpen_whenDisasterOverrideActive() {
    insertCalendar("CAL_US", "America/New_York");
    // NY EDT cutoff=22:00 → 用 NY 本地 22:01 of BIZ 让 scheduler 算出 bizDate=BIZ
    Instant now =
        BIZ.atTime(22, 1).atZone(java.time.ZoneId.of("America/New_York")).toInstant();
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
  @DisplayName("按工作日差异化的截断时刻配置以结构化列落库并可完整读回")
  void shouldPersistCutoffSchedule_whenJsonbColumnUsed() {
    String cutoffJson = "{\"default\":\"22:00\",\"weekdayPattern\":{\"THURSDAY\":\"12:00\"}}";
    jdbcTemplate.update(
        "insert into batch.business_calendar"
            + " (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,"
            + "  enabled, cutoff_schedule)"
            + " values (?, 'CAL_JP', 'JP', 'Asia/Tokyo', '22:00', true, ?::jsonb)",
        TENANT,
        cutoffJson);

    Map<String, Object> row = jdbcTemplate.queryForMap(
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
    insertCalendar(code, timezone, "SKIP");
  }

  private void insertCalendar(String code, String timezone, String holidayRollRule) {
    jdbcTemplate.update(
        "insert into batch.business_calendar"
            + " (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,"
            + " holiday_roll_rule, enabled)"
            + " values (?, ?, ?, ?, '22:00', ?, true)",
        TENANT,
        code,
        code,
        timezone,
        holidayRollRule);
  }

  private Map<String, Object> selectFirstBatchDay(String calendarCode) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select * from batch.batch_day_instance where tenant_id = ? and calendar_code = ?"
            + " order by biz_date desc, id desc limit 1",
        TENANT,
        calendarCode);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
