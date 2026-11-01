package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupControlService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.BatchDayCatchupPreviewService;
import io.github.pinpols.batch.orchestrator.application.service.calendar.CalendarDependencyWaitGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.entity.CalendarDependencyWaitEntity;
import io.github.pinpols.batch.orchestrator.domain.query.SensorFileArrivalLatestArrivalQuery;
import io.github.pinpols.batch.orchestrator.domain.view.SensorFileArrivalView;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayOpenScheduler;
import io.github.pinpols.batch.orchestrator.mapper.CalendarDependencyWaitMapper;
import io.github.pinpols.batch.orchestrator.mapper.SensorFileArrivalMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  private final DataSource dataSource;

  private final SensorFileArrivalMapper sensorFileArrivalMapper;

  private final CalendarDependencyWaitMapper calendarDependencyWaitMapper;

  private final CalendarDependencyWaitGovernanceService calendarDependencyWaitGovernanceService;

  private final BatchDayCatchupPreviewService batchDayCatchupPreviewService;

  private final BatchDayCatchupControlService batchDayCatchupControlService;

  private final PlatformTransactionManager transactionManager;

  @Autowired
  MultiCalendarCoordinationIntegrationTest(
      DataSource dataSource,
      SensorFileArrivalMapper sensorFileArrivalMapper,
      CalendarDependencyWaitMapper calendarDependencyWaitMapper,
      CalendarDependencyWaitGovernanceService calendarDependencyWaitGovernanceService,
      BatchDayCatchupPreviewService batchDayCatchupPreviewService,
      BatchDayCatchupControlService batchDayCatchupControlService,
      PlatformTransactionManager transactionManager) {
    this.dataSource = dataSource;
    this.sensorFileArrivalMapper = sensorFileArrivalMapper;
    this.calendarDependencyWaitMapper = calendarDependencyWaitMapper;
    this.calendarDependencyWaitGovernanceService = calendarDependencyWaitGovernanceService;
    this.batchDayCatchupPreviewService = batchDayCatchupPreviewService;
    this.batchDayCatchupControlService = batchDayCatchupControlService;
    this.transactionManager = transactionManager;
  }

  @BeforeEach
  void seedCleanCalendars() {
    final String deleteBatchDayOpenCursorSql = """
        delete from batch.batch_day_open_cursor where tenant_id = ?
    """;
    final String deleteBatchDayCatchupControlEventSql = """
        delete from batch.batch_day_catchup_control_event where tenant_id = ?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = ?
    """;
    final String deleteCalendarDependencySql = """
        delete from batch.calendar_dependency where tenant_id = ?
    """;
    final String deleteCalendarDependencyWaitSql = """
        delete from batch.calendar_dependency_wait where tenant_id = ?
    """;
    final String deleteDisasterDayOverrideSql = """
        delete from batch.disaster_day_override where tenant_id = ?
    """;
    final String deleteBatchDayInstanceSql = """
        delete from batch.batch_day_instance where tenant_id = ?
    """;
    final String deleteCalendarHolidaySql = """
        delete from batch.calendar_holiday where tenant_id = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;

    jdbcTemplate.update(deleteBatchDayOpenCursorSql, TENANT);
    jdbcTemplate.update(deleteBatchDayCatchupControlEventSql, TENANT);
    jdbcTemplate.update(deleteBatchDayCatchupControlSql, TENANT);
    jdbcTemplate.update(deleteCalendarDependencySql, TENANT);
    jdbcTemplate.update(deleteCalendarDependencyWaitSql, TENANT);
    jdbcTemplate.update(deleteDisasterDayOverrideSql, TENANT);
    jdbcTemplate.update(deleteBatchDayInstanceSql, TENANT);
    jdbcTemplate.update(deleteCalendarHolidaySql, TENANT);
    jdbcTemplate.update(deleteBusinessCalendarSql, TENANT);
  }

  @Test
  @DisplayName("SKIP 假日按真实日历记录创建带审计的 SKIPPED 批量日")
  void shouldPersistSkippedHolidayFromCalendarConfiguration() {
    final String insertCalendarHolidaySql = """
          insert into batch.calendar_holiday
            (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
          select ?, id, ?, 'HOLIDAY', 'test holiday', 'CALENDAR'
          from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_HOLIDAY'
          """;

    insertCalendar("CAL_HOLIDAY", "Asia/Shanghai", "SKIP");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT);
    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(now);

    Map<String, Object> row = selectFirstBatchDay("CAL_HOLIDAY");
    assertThat(row).isNotNull();
    assertThat(row.get("biz_date").toString()).isEqualTo(BIZ.toString());
    assertThat(row.get("day_status")).isEqualTo("SKIPPED");
    assertThat(row.get("operation_reason")).isEqualTo("CALENDAR_HOLIDAY_SKIP");
  }

  @Test
  @DisplayName("节假日跳过等待已接纳请求结束后再推进扫描游标")
  void shouldDeferHolidaySkipWhileTriggerRequestCanStillLaunch() {
    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
        select ?, id, ?, 'HOLIDAY', 'trigger race', 'CALENDAR' from batch.business_calendar where tenant_id = ?\s\
        and calendar_code = ?\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, ?) returning id\
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status, trace_id) values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED', ?)\
        """;
    final String selectDayStatusSql = """
      select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ? and biz_date = ?\
      """;
    final String selectLastScannedBizDateSql = """
      select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ? and calendar_code = ?\
      """;
    final String updateTriggerRequestSql = """
      update batch.trigger_request set request_status = 'REJECTED' where tenant_id = ? and request_id = ?\
      """;
    final String selectDayStatusSql2 = """
      select day_status, operation_reason from batch.batch_day_instance where tenant_id = ?\s\
      and calendar_code = ? and biz_date = ?\
      """;

    String calendarCode = "CAL_SKIP_RACE_" + System.nanoTime();
    String workflowCode = "WF_SKIP_RACE_" + System.nanoTime();
    String requestId = "calendar-skip-race-" + System.nanoTime();
    insertCalendar(calendarCode, "Asia/Shanghai", "SKIP");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        workflowCode,
        "Calendar skip race workflow",
        calendarCode);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId,
        workflowDefinitionId,
        BIZ,
        "dedup-" + requestId,
        "trace-" + requestId);

    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(now);

    List<Map<String, Object>> selectedDayStatusValues =
        jdbcTemplate.queryForList(selectDayStatusSql, TENANT, calendarCode, BIZ);
    assertThat(selectedDayStatusValues).isEmpty();
    LocalDate selectedLastScannedBizDate = jdbcTemplate.queryForObject(
        selectLastScannedBizDateSql, LocalDate.class, TENANT, calendarCode);
    assertThat(selectedLastScannedBizDate).isEqualTo(BIZ.minusDays(1));

    jdbcTemplate.update(updateTriggerRequestSql, TENANT, requestId);
    scheduler.openDueBatchDays(now.plusSeconds(1));

    Map<String, Object> skipped =
        jdbcTemplate.queryForMap(selectDayStatusSql2, TENANT, calendarCode, BIZ);
    assertThat(skipped.get("day_status")).isEqualTo("SKIPPED");
    assertThat(skipped.get("operation_reason")).isEqualTo("CALENDAR_HOLIDAY_SKIP");
    LocalDate selectedLastScannedBizDateRead2 = jdbcTemplate.queryForObject(
        selectLastScannedBizDateSql, LocalDate.class, TENANT, calendarCode);
    assertThat(selectedLastScannedBizDateRead2).isEqualTo(BIZ);
  }

  @Test
  @DisplayName("子 Job TriggerRequest 也阻止独立批日的节假日终态跳过")
  void shouldDeferHolidaySkipWhileChildWorkflowRequestCanStillLaunch() {
    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
        select ?, id, ?, 'HOLIDAY', 'child trigger race', 'CALENDAR' from batch.business_calendar\s\
        where tenant_id = ? and calendar_code = ?\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, ?) returning id\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status)\s\
        values (?, ?, ?, 'RUNNING') returning id\
        """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, ?, 'TASK', 'READY')\
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, ?)\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectLastScannedBizDateSql = """
        select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ? and calendar_code = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;

    String calendarCode = "CAL_CHILD_SKIP_" + System.nanoTime();
    String workflowCode = "WF_CHILD_SKIP_" + System.nanoTime();
    String requestId = "child-calendar-skip-" + System.nanoTime();
    String nodeCode = "CHILD_JOB_" + System.nanoTime();
    insertCalendar(calendarCode, "Asia/Shanghai", "SKIP");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        workflowCode,
        "Child calendar skip workflow",
        calendarCode);
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, TENANT, workflowDefinitionId, BIZ);
    jdbcTemplate.update(insertWorkflowNodeRunSql, workflowRunId, nodeCode);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        TENANT,
        requestId,
        nodeCode,
        BIZ,
        "dedup-" + requestId,
        workflowRunId,
        nodeCode);

    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(now);

    List<Map<String, Object>> selectedDayStatusValues =
        jdbcTemplate.queryForList(selectDayStatusSql, TENANT, calendarCode, BIZ);
    assertThat(selectedDayStatusValues).isEmpty();
    LocalDate selectedLastScannedBizDate = jdbcTemplate.queryForObject(
        selectLastScannedBizDateSql, LocalDate.class, TENANT, calendarCode);
    assertThat(selectedLastScannedBizDate).isEqualTo(BIZ.minusDays(1));
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, requestId);
    assertThat(selectedRequestStatus).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("节假日终态跳过先持围栏时 Trigger 等待并被拒绝")
  void shouldSerializeHolidaySkipBeforeTriggerAdmission() throws Exception {
    final String selectPgAdvisoryLockSql = """
        select pg_advisory_lock(?)
    """;
    final String selectPgAdvisoryUnlockSql = """
        select pg_advisory_unlock(?)
    """;

    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
        select ?, id, ?, 'HOLIDAY', 'terminal fence', 'CALENDAR' from batch.business_calendar\s\
        where tenant_id = ? and calendar_code = ?\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, ?) returning id\
        """;
    final String selectPidSql = """
        select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%insert into batch.batch_day_instance%%' limit 1\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    String calendarCode = "CAL_SKIP_FENCE_" + System.nanoTime();
    String workflowCode = "WF_SKIP_FENCE_" + System.nanoTime();
    String requestId = "calendar-skip-fence-" + System.nanoTime();
    insertCalendar(calendarCode, "Asia/Shanghai", "SKIP");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        TENANT,
        workflowCode,
        "Calendar skip fence workflow",
        calendarCode);

    String triggerName = "skip_fence_" + UUID.randomUUID().toString().replace('-', '_');
    String functionName = triggerName + "_fn";
    long advisoryKey = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    final String createFunctionSql =
        """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = '%s' and new.calendar_code = '%s' and new.biz_date = date '%s'
             and new.day_status = 'SKIPPED' then
            perform pg_advisory_xact_lock(%d);
          end if;
          return new;
        end;
        $$
        """.formatted(functionName, TENANT, calendarCode, BIZ, advisoryKey);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.batch_day_instance
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);

    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    try (var executor = Executors.newFixedThreadPool(2);
        Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(true);
      try (var lock = blocker.prepareStatement(selectPgAdvisoryLockSql)) {
        lock.setLong(1, advisoryKey);
        lock.execute();
      }
      int blockerPid = backendPid(blocker);
      Future<?> opening = executor.submit(() -> scheduler.openDueBatchDays(now));
      Future<String> admission = null;
      try {
        awaitBlockedBy(blockerPid);
        Integer openingPid = jdbcTemplate.queryForObject(selectPidSql, Integer.class, blockerPid);
        assertThat(openingPid).isNotNull();

        admission = executor.submit(
            () -> tryInsertWorkflowRequest(TENANT, requestId, workflowDefinitionId, BIZ));
        awaitBlockedBy(openingPid);
        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }

        opening.get(10, TimeUnit.SECONDS);
        assertThat(admission.get(10, TimeUnit.SECONDS)).isEqualTo("BDA01");
        String selectedDayStatus = jdbcTemplate.queryForObject(
            selectDayStatusSql, String.class, TENANT, calendarCode, BIZ);
        assertThat(selectedDayStatus).isEqualTo("SKIPPED");
        Integer selectedTriggerRequestCount =
            jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, requestId);
        assertThat(selectedTriggerRequestCount).isZero();
      } finally {
        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }
        opening.get(10, TimeUnit.SECONDS);
        if (admission != null) admission.get(10, TimeUnit.SECONDS);
      }
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.batch_day_instance\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }
  }

  private String tryInsertWorkflowRequest(
      String tenantId, String id, Long workflowDefinitionId, LocalDate bizDate) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status, trace_id) values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED', ?)\
        """;

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(insertTriggerRequestSql)) {
        statement.setString(1, tenantId);
        statement.setString(2, id);
        statement.setLong(3, workflowDefinitionId);
        statement.setObject(4, bizDate);
        statement.setString(5, "dedup-" + id);
        statement.setString(6, "trace-" + id);
        statement.executeUpdate();
      }
      connection.commit();
      return "accepted";
    } catch (SQLException failure) {
      return failure.getSQLState();
    }
  }

  private void awaitBlockedBy(int blockerPid) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("PostgreSQL transaction did not reach the expected lock barrier");
  }

  private int backendPid(Connection connection) throws SQLException {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      result.next();
      return result.getInt(1);
    }
  }

  @Test
  @DisplayName("上游节假日跳过不满足下游 WAIT_SETTLED，只有审计人工释放后下游才开放")
  void shouldKeepDownstreamWaitingWhenUpstreamHolidayIsSkippedUntilManualRelease() {
    final String insertCalendarHolidaySql = """
          insert into batch.calendar_holiday
            (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
          select ?, id, ?, 'HOLIDAY', 'upstream holiday', 'CALENDAR'
          from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_HOLIDAY_UP'
          """;
    final String insertCalendarDependencySql = """
      insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
      values (?, 'CAL_HOLIDAY_UP', 'cal_holiday_down', 'WAIT_SETTLED', true)\
      """;
    final String selectWaitStatusSql = """
      select wait_status, blocked_reason, manual_action from batch.calendar_dependency_wait where tenant_id = ?\s\
      and calendar_code = 'cal_holiday_down' and biz_date = ?\
      """;
    final String selectDayStatusSql = """
      select day_status, operated_by, operation_reason from batch.batch_day_instance where tenant_id = ?\s\
      and calendar_code = 'cal_holiday_down' and biz_date = ?\
      """;
    final String selectWaitStatusSql2 = """
      select wait_status, manual_operator_id, manual_reason from batch.calendar_dependency_wait\s\
      where tenant_id = ? and calendar_code = 'cal_holiday_down' and biz_date = ?\
      """;

    insertCalendar("CAL_HOLIDAY_UP", "Asia/Shanghai", "SKIP");
    insertCalendar("cal_holiday_down", "Asia/Shanghai");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT);
    jdbcTemplate.update(insertCalendarDependencySql, TENANT);
    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();

    scheduler.openDueBatchDays(now);

    Map<String, Object> upstream = selectFirstBatchDay("CAL_HOLIDAY_UP");
    assertThat(upstream).isNotNull();
    assertThat(upstream.get("day_status")).isEqualTo("SKIPPED");
    assertThat(selectFirstBatchDay("CAL_HOLIDAY_DOWN")).isNull();
    // 按最新的持久化调度状态协调 Quartz，不改写该状态。
    // 只确保存在持久化恢复目标；不要启用或注册主调度。
    scheduler.openDueBatchDays(now.plusSeconds(1));
    Map<String, Object> waiting = jdbcTemplate.queryForMap(selectWaitStatusSql, TENANT, BIZ);
    assertThat(waiting.get("wait_status")).isEqualTo("WAITING");
    assertThat(waiting.get("blocked_reason").toString()).contains(":status=SKIPPED");
    assertThat(waiting.get("manual_action")).isNull();

    calendarDependencyWaitGovernanceService.requestManualAction(
        new CalendarDependencyWaitGovernanceService.ManualActionCommand(
            TENANT, "CAL_HOLIDAY_DOWN", BIZ, "RELEASE", "ops-user", "holiday upstream waived"));
    scheduler.openDueBatchDays(now.plusSeconds(2));

    Map<String, Object> downstream = jdbcTemplate.queryForMap(selectDayStatusSql, TENANT, BIZ);
    assertThat(downstream.get("day_status")).isEqualTo("OPEN");
    assertThat(downstream.get("operated_by")).isEqualTo("ops-user");
    assertThat(downstream.get("operation_reason").toString())
        .contains("MANUAL_CALENDAR_DEPENDENCY_RELEASE:holiday upstream waived");
    Map<String, Object> openedWait = jdbcTemplate.queryForMap(selectWaitStatusSql2, TENANT, BIZ);
    assertThat(openedWait.get("wait_status")).isEqualTo("OPENED");
    assertThat(openedWait.get("manual_operator_id")).isEqualTo("ops-user");
    assertThat(openedWait.get("manual_reason")).isEqualTo("holiday upstream waived");
  }

  @Test
  @DisplayName("调度器停机跨越多个业务日后按顺序补建缺失批量日")
  void shouldCatchUpMissingBatchDaysAfterSchedulerDowntime() {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at)\s\
        values (?, 'CAL_CATCHUP', ?, 'SETTLED', current_timestamp)\
        """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_CATCHUP'\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_CATCHUP'\s\
        order by biz_date\
        """;

    insertCalendar("CAL_CATCHUP", "Asia/Shanghai");
    LocalDate lastOpened = BIZ.minusDays(20);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, lastOpened);

    Instant recoveredAt = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(recoveredAt);
    Integer firstScanCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(firstScanCount).isEqualTo(15);
    // 在单个租户、Workflow 和调度版本内有界读取尚未观察到的触发记录。
    scheduler.openDueBatchDays(recoveredAt.plusSeconds(60));

    List<LocalDate> openedDates =
        jdbcTemplate.queryForList(selectBizDateSql, LocalDate.class, TENANT);
    assertThat(openedDates)
        .containsExactlyElementsOf(
            Stream.iterate(lastOpened, date -> date.plusDays(1)).limit(21).toList());
  }

  @Test
  @DisplayName("数据库拒绝无同租户日历的 catch-up 控制并保护被引用日历")
  void shouldEnforceCatchupControlCalendarReference() {
    final String insertBatchDayCatchupControlSql = """
      insert into batch.batch_day_catchup_control (tenant_id, calendar_code) values (?, ?)\
      """;
    final String insertBatchDayCatchupControlSql2 = """
      insert into batch.batch_day_catchup_control (tenant_id, calendar_code) values (?, ?)\
      """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set calendar_code = ? where tenant_id = ? and calendar_code = ?\
        """;

    insertCalendar("CAL_CONTROL_FK", "Asia/Shanghai");

    assertThatThrownBy(() ->
            jdbcTemplate.update(insertBatchDayCatchupControlSql, TENANT, "CAL_CONTROL_ORPHAN"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update(insertBatchDayCatchupControlSql, "t2", "CAL_CONTROL_FK"))
        .isInstanceOf(DataIntegrityViolationException.class);

    jdbcTemplate.update(insertBatchDayCatchupControlSql2, TENANT, "CAL_CONTROL_FK");
    assertThatThrownBy(
            () -> jdbcTemplate.update(deleteBusinessCalendarSql, TENANT, "CAL_CONTROL_FK"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbcTemplate.update(
            updateBusinessCalendarSql, "CAL_CONTROL_RENAMED", TENANT, "CAL_CONTROL_FK"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("日历删除先取得父锁时，控制更新等待后映射外键冲突且回滚审计")
  void shouldMapConcurrentCalendarDeleteToConflictWithoutAudit() throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String deleteBusinessCalendarSql2 = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;

    final String selectCountSql = """
      select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid)) and state = 'active'\s\
      and query ilike '%%batch_day_catchup_control%%'\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.batch_day_catchup_control where tenant_id = ? and calendar_code = ?\
      """;
    final String selectCountSql3 = """
      select count(*) from batch.batch_day_catchup_control_event where tenant_id = ? and calendar_code = ?\
      """;
    final String deleteBatchDayCatchupControlEventSql = """
        delete from batch.batch_day_catchup_control_event where tenant_id = ? and calendar_code = ?\
        """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = ? and calendar_code = ?\
        """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;

    String calendarCode = "CAL_CONTROL_RACE";
    insertCalendar(calendarCode, "Asia/Shanghai");
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try (Connection deleteTransaction = dataSource.getConnection()) {
      deleteTransaction.setAutoCommit(false);
      int deletePid;
      try (PreparedStatement pidQuery = deleteTransaction.prepareStatement(selectPgBackendPidSql)) {
        try (ResultSet resultSet = pidQuery.executeQuery()) {
          assertThat(resultSet.next()).isTrue();
          deletePid = resultSet.getInt(1);
        }
      }
      try (PreparedStatement deleteCalendar =
          deleteTransaction.prepareStatement(deleteBusinessCalendarSql2)) {
        deleteCalendar.setString(1, TENANT);
        deleteCalendar.setString(2, calendarCode);
        assertThat(deleteCalendar.executeUpdate()).isEqualTo(1);
      }

      Future<?> concurrentChange = executor.submit(
          () -> batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
              TENANT, calendarCode, "PAUSE", "ops-race", "calendar deletion race")));
      try {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
          Integer selectedPgStatActivityCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, deletePid);
          assertThat(selectedPgStatActivityCount).isEqualTo(1);
        });
        deleteTransaction.commit();
      } catch (RuntimeException | Error failure) {
        deleteTransaction.rollback();
        concurrentChange.cancel(true);
        throw failure;
      }

      assertThatThrownBy(() -> concurrentChange.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .satisfies(failure -> {
            Throwable cause = ((ExecutionException) failure).getCause();
            assertThat(cause).isInstanceOf(OperationFailure.class);
            assertThat(((OperationFailure) cause).kind()).isEqualTo(Kind.CONFLICT);
          });
      Integer selectedBatchDayCatchupControlCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, calendarCode);
      assertThat(selectedBatchDayCatchupControlCount).isZero();
      Integer selectedBatchDayCatchupControlEventCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, calendarCode);
      assertThat(selectedBatchDayCatchupControlEventCount).isZero();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      jdbcTemplate.update(deleteBatchDayCatchupControlEventSql, TENANT, calendarCode);
      jdbcTemplate.update(deleteBatchDayCatchupControlSql, TENANT, calendarCode);
      jdbcTemplate.update(deleteBusinessCalendarSql, TENANT, calendarCode);
    }
  }

  @Test
  @DisplayName("日切预览按租户 RLS 读取游标且不修改日切状态")
  void shouldPreviewCatchupWithoutAdvancingCursorOrCreatingBatchDays() {
    final String insertBatchDayOpenCursorSql = """
        insert into batch.batch_day_open_cursor (tenant_id, calendar_code, last_scanned_biz_date)\s\
        values (?, 'CAL_PREVIEW', ?)\
        """;
    final String selectLastScannedBizDateSql = """
        select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ?\s\
        and calendar_code = 'CAL_PREVIEW'\
        """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_PREVIEW'\
        """;

    insertCalendar("CAL_PREVIEW", "Asia/Shanghai");
    LocalDate currentBizDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    LocalDate cursorDate = currentBizDate.minusDays(3);
    jdbcTemplate.update(insertBatchDayOpenCursorSql, TENANT, cursorDate);

    var preview = batchDayCatchupPreviewService.preview(TENANT, "CAL_PREVIEW");

    assertThat(preview.cursorSource()).isEqualTo("PERSISTED_CURSOR");
    assertThat(preview.lastScannedBizDate()).isEqualTo(cursorDate);
    assertThat(preview.nextScanRawBizDates())
        .isNotEmpty()
        .first()
        .isEqualTo(cursorDate.plusDays(1));
    LocalDate persistedCursor =
        jdbcTemplate.queryForObject(selectLastScannedBizDateSql, LocalDate.class, TENANT);
    assertThat(persistedCursor).isEqualTo(cursorDate);
    Integer batchDayCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(batchDayCount).isZero();
  }

  @Test
  @DisplayName("历史缺口清单只返回限定日期范围内无记录的候选且不写运行态")
  void shouldInventoryHistoricalGapCandidatesWithoutWrites() {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at)\s\
        values (?, 'CAL_HISTORY', ?, 'SETTLED', current_timestamp), (?, 'CAL_HISTORY', ?, 'SKIPPED',\s\
        current_timestamp)\
        """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_HISTORY'\
        """;

    insertCalendar("CAL_HISTORY", "Asia/Shanghai");
    LocalDate from = LocalDate.of(2026, 4, 1);
    LocalDate through = LocalDate.of(2026, 4, 4);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, from, TENANT, from.plusDays(2));

    var inventory =
        batchDayCatchupPreviewService.inventoryHistoricalGaps(TENANT, "CAL_HISTORY", from, through);

    assertThat(inventory.inspectedDateCount()).isEqualTo(4);
    assertThat(inventory.missingRecordCandidates())
        .containsExactly(from.plusDays(1), from.plusDays(3));
    Integer persistedCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(persistedCount).isEqualTo(2);
    assertThatThrownBy(() -> batchDayCatchupPreviewService.inventoryHistoricalGaps(
            TENANT, "CAL_HISTORY", from, from.plusDays(90)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
  }

  @Test
  @DisplayName("管理员暂停后调度器保留 catch-up 游标,恢复后继续并保留不可变审计事件")
  void shouldPauseAndResumeCatchupWithTenantScopedAudit() {
    final String insertBatchDayOpenCursorSql = """
      insert into batch.batch_day_open_cursor (tenant_id, calendar_code, last_scanned_biz_date)\s\
      values (?, 'CAL_PAUSE', ?)\
      """;
    final String selectLastScannedBizDateSql = """
      select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ?\s\
      and calendar_code = 'CAL_PAUSE'\
      """;
    final String selectCountSql = """
      select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_PAUSE'\s\
      and biz_date = ?\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.batch_day_catchup_control_event where tenant_id = ?\s\
      and calendar_code = 'CAL_PAUSE'\
      """;

    insertCalendar("CAL_PAUSE", "Asia/Shanghai");
    Instant now = Instant.now();
    var localNow = now.atZone(ZoneId.of("Asia/Shanghai"));
    LocalDate currentBizDate = localNow.toLocalTime().isBefore(LocalTime.of(22, 0))
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    LocalDate cursorDate = currentBizDate.minusDays(3);
    jdbcTemplate.update(insertBatchDayOpenCursorSql, TENANT, cursorDate);
    var paused =
        batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
            TENANT, "CAL_PAUSE", "PAUSE", "ops-1", "maintenance window"));
    scheduler.openDueBatchDays(now);
    LocalDate cursorDuringPause =
        jdbcTemplate.queryForObject(selectLastScannedBizDateSql, LocalDate.class, TENANT);
    assertThat(paused.paused()).isTrue();
    assertThat(cursorDuringPause).isEqualTo(cursorDate);
    Integer currentDayCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, currentBizDate);
    assertThat(currentDayCount).isEqualTo(1);
    assertThat(batchDayCatchupPreviewService.preview(TENANT, "CAL_PAUSE").catchupPaused())
        .isTrue();

    var resumed =
        batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
            TENANT, "CAL_PAUSE", "RESUME", "ops-2", "maintenance complete"));
    Integer eventCount = jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT);
    assertThat(resumed.paused()).isFalse();
    assertThat(eventCount).isEqualTo(2);
  }

  @Test
  @DisplayName("非绕过 RLS 角色可通过控制服务读取并审计补齐暂停状态")
  void shouldUseDatabaseTenantContextForCatchupControl() {
    final String queryStatementSql = "RELEASE";

    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_catchup_rls_test') THEN
            CREATE ROLE batch_catchup_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;

    final String grantUsageSql = """
      GRANT USAGE ON SCHEMA batch TO batch_catchup_rls_test
  """;
    final String grantSelectSql = """
      GRANT SELECT ON ALL TABLES IN SCHEMA batch TO batch_catchup_rls_test
      """;
    // FOR KEY SHARE 即使不修改行，也要求至少一列 UPDATE 权限；仅补锁查询所需的列权限。
    final String grantInstanceReferenceLockSql = """
        GRANT UPDATE (updated_at) ON batch.job_instance TO batch_catchup_rls_test
        """;
    final String grantInsertSql = """
      GRANT INSERT, UPDATE ON batch.batch_day_open_cursor TO batch_catchup_rls_test
      """;
    final String grantInsertSql2 = """
      GRANT INSERT ON batch.batch_day_instance, batch.job_execution_log TO batch_catchup_rls_test\
      """;
    final String grantInsertSql3 = """
      GRANT INSERT, UPDATE, DELETE ON batch.batch_day_catchup_control, batch.batch_day_catchup_control_event TO\s\
      batch_catchup_rls_test\
      """;
    final String grantUpdateSql = """
      GRANT UPDATE ON batch.calendar_dependency_wait TO batch_catchup_rls_test
      """;
    final String grantUsageSql2 = """
      GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO batch_catchup_rls_test
      """;
    final String setLocalSql = """
        SET LOCAL ROLE batch_catchup_rls_test
    """;

    final String selectCountSql = """
        select count(*) from batch.batch_day_catchup_control_event where tenant_id = ?\s\
        and calendar_code = 'cal_rls'\
        """;
    final String selectManualActionSql = """
        select manual_action from batch.calendar_dependency_wait where tenant_id = ?\s\
        and calendar_code = 'cal_rls' and biz_date = ?\
        """;
    final String selectLastScannedBizDateSql = """
        select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ?\s\
        and calendar_code = 'cal_rls'\
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'cal_rls'\s\
        and biz_date = ?\
        """;

    insertCalendar("cal_rls", "Asia/Shanghai");
    Instant waitCreatedAt = Instant.now();
    calendarDependencyWaitMapper.upsertWaiting(CalendarDependencyWaitEntity.builder()
        .tenantId(TENANT)
        .calendarCode("cal_rls")
        .bizDate(BIZ)
        .blockedReason("BLOCKED_BY_UPSTREAM_CALENDAR:CAL_UPSTREAM")
        .firstWaitingAt(waitCreatedAt)
        .lastCheckedAt(waitCreatedAt)
        .timezoneSnapshot("Asia/Shanghai")
        .dstPolicySnapshot("RUN_AT_NEXT_VALID_TIME/RUN_ONCE_EARLIER_OFFSET")
        .build());
    jdbcTemplate.execute(doStatementSql);
    jdbcTemplate.execute(grantUsageSql);
    jdbcTemplate.execute(grantSelectSql);
    jdbcTemplate.execute(grantInstanceReferenceLockSql);
    jdbcTemplate.execute(grantInsertSql);
    jdbcTemplate.execute(grantInsertSql2);
    jdbcTemplate.execute(grantInsertSql3);
    jdbcTemplate.execute(grantUpdateSql);
    jdbcTemplate.execute(grantUsageSql2);

    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    var paused = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
              TENANT, "cal_rls", "PAUSE", "ops-rls", "verify tenant RLS")));
    });

    assertThat(paused).isNotNull();
    assertThat(paused.paused()).isTrue();
    Integer selectedBatchDayCatchupControlEventCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedBatchDayCatchupControlEventCount).isEqualTo(1);
    var observed = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
        assertThat(batchDayCatchupControlService.current(TENANT, "cal_rls").paused())
            .isTrue();
        assertThat(batchDayCatchupPreviewService.preview(TENANT, "cal_rls").catchupPaused())
            .isTrue();
        return true;
      });
    });
    assertThat(observed).isTrue();
    var governanceObserved = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
        var waits = calendarDependencyWaitGovernanceService.list(TENANT, "CAL_RLS", "WAITING", 10);
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0).calendarCode()).isEqualTo("cal_rls");
        var updatedWait = calendarDependencyWaitGovernanceService.requestManualAction(
            new CalendarDependencyWaitGovernanceService.ManualActionCommand(
                TENANT, "CAL_RLS", BIZ, "RELEASE", "ops-rls", "release after review"));
        assertThat(updatedWait.calendarCode()).isEqualTo("cal_rls");
        return true;
      });
    });
    assertThat(governanceObserved).isTrue();
    String selectedManualAction =
        jdbcTemplate.queryForObject(selectManualActionSql, String.class, TENANT, BIZ);
    assertThat(selectedManualAction).isEqualTo(queryStatementSql);

    var openedUnderRlsRole = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
        batchDayCatchupControlService.change(new BatchDayCatchupControlService.PauseCommand(
            TENANT, "cal_rls", "RESUME", "ops-rls", "resume scheduler RLS verification"));
        scheduler.openDueBatchDays(BIZ.atTime(15, 0).toInstant(ZoneOffset.UTC));
        return true;
      });
    });
    assertThat(openedUnderRlsRole).isTrue();
    LocalDate selectedLastScannedBizDate =
        jdbcTemplate.queryForObject(selectLastScannedBizDateSql, LocalDate.class, TENANT);
    assertThat(selectedLastScannedBizDate).isEqualTo(BIZ);
    String selectedDayStatus =
        jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, BIZ);
    assertThat(selectedDayStatus).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("NEXT_WORKDAY 提前创建滚动目标后仍推进原始日期游标")
  void shouldAdvanceRawDateCursorWhenHolidayRollCreatesFutureDay() {
    final String insertCalendarHolidaySql = """
          insert into batch.calendar_holiday
            (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
          select ?, id, ?, 'HOLIDAY', 'roll start', 'CALENDAR'
          from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'
          """;
    final String insertCalendarHolidaySql2 = """
        insert into batch.calendar_holiday
          (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
        select ?, id, ?, 'HOLIDAY', 'roll target holiday', 'CALENDAR'
        from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at)\s\
        values (?, 'CAL_ROLL_NEXT', ?, 'SETTLED', current_timestamp)\
        """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_ROLL_NEXT'\s\
        and biz_date = ?\
        """;
    final String selectLastScannedBizDateSql = """
        select last_scanned_biz_date from batch.batch_day_open_cursor where tenant_id = ?\s\
        and calendar_code = 'CAL_ROLL_NEXT'\
        """;

    insertCalendar("CAL_ROLL_NEXT", "Asia/Shanghai", "NEXT_WORKDAY");
    jdbcTemplate.update(insertCalendarHolidaySql, TENANT, BIZ, TENANT);
    jdbcTemplate.update(insertCalendarHolidaySql2, TENANT, BIZ.plusDays(1), TENANT);
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, BIZ.minusDays(1));

    Instant firstDate = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(firstDate);
    Integer rolledRows =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, BIZ.plusDays(2));
    assertThat(rolledRows).isEqualTo(1);

    scheduler.openDueBatchDays(firstDate.plusSeconds(86_400));
    LocalDate cursor =
        jdbcTemplate.queryForObject(selectLastScannedBizDateSql, LocalDate.class, TENANT);
    assertThat(cursor).isEqualTo(BIZ.plusDays(1));
  }

  @Test
  @DisplayName("上游日历未结算时下游日历不开,上游结算后下游日历可开")
  void shouldBlockDownstreamCalendar_whenUpstreamNotSettled() {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'SETTLED', settled_at = current_timestamp\s\
        where tenant_id = ? and calendar_code = 'CAL_CN'\
        """;

    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Hong_Kong");
    // HK 依赖 CN SETTLED
    jdbcTemplate.update(insertCalendarDependencySql, TENANT);

    // 第一轮：CN 还没 SETTLED → HK 应被 deferred / 不开
    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("UTC")).toInstant();
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
    jdbcTemplate.update(updateBatchDayInstanceSql, TENANT);

    // 第二轮：CN SETTLED 后 HK 应能开
    scheduler.openDueBatchDays(now.plusSeconds(1));
    Map<String, Object> hkRowAfter = selectFirstBatchDay("CAL_HK");
    assertThat(hkRowAfter).isNotNull();
    assertThat(hkRowAfter.get("day_status")).isIn("OPEN", "IN_FLIGHT");
  }

  @Test
  @DisplayName("日历依赖只允许引用同租户日历并阻止删除仍被引用的日历")
  void shouldEnforceSameTenantCalendarDependencyReferences() {
    final String insertBusinessCalendarSql = """
      insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
      enabled) values ('t2', ?, 'foreign', 'UTC', '22:00', true)\
      """;
    final String insertCalendarDependencySql = """
      insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
      values (?, ?, 'CAL_DEP_DOWN', 'WAIT_SETTLED', true)\
      """;
    final String insertCalendarDependencySql2 = """
      insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
      values (?, 'CAL_DEP_UP', ?, 'WAIT_SETTLED', true)\
      """;
    final String deleteBusinessCalendarSql = """
      delete from batch.business_calendar where tenant_id = ? and calendar_code = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.calendar_dependency where tenant_id = ? and upstream_code = 'CAL_DEP_UP'\
      """;
    final String deleteBusinessCalendarSql2 = """
      delete from batch.business_calendar where tenant_id = 't2' and calendar_code = ?
  """;

    String foreignCalendar = "CAL_DEP_FOREIGN_" + System.nanoTime();
    insertCalendar("CAL_DEP_UP", "Asia/Shanghai");
    insertCalendar("CAL_DEP_DOWN", "Asia/Shanghai");
    jdbcTemplate.update(insertBusinessCalendarSql, foreignCalendar);

    try {
      assertThatThrownBy(
              () -> jdbcTemplate.update(insertCalendarDependencySql, TENANT, foreignCalendar))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () -> jdbcTemplate.update(insertCalendarDependencySql2, TENANT, foreignCalendar))
          .isInstanceOf(DataIntegrityViolationException.class);

      insertCalendarDependency("CAL_DEP_UP", "CAL_DEP_DOWN");
      assertThatThrownBy(() -> jdbcTemplate.update(deleteBusinessCalendarSql, TENANT, "CAL_DEP_UP"))
          .isInstanceOf(DataIntegrityViolationException.class);
      Integer selectedCalendarDependencyCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
      assertThat(selectedCalendarDependencyCount).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteBusinessCalendarSql2, foreignCalendar);
    }
  }

  @Test
  @DisplayName("日历节假日直接写入必须与引用日历属于同一租户")
  void shouldEnforceSameTenantCalendarHolidayReference() {
    final String selectIdSql = """
      select id from batch.business_calendar where tenant_id = ? and calendar_code = ?
  """;
    final String insertBusinessCalendarSql = """
      insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
      enabled) values ('t2', ?, 'foreign', 'UTC', '22:00', true)\
      """;
    final String selectIdSql2 = """
      select id from batch.business_calendar where tenant_id = 't2' and calendar_code = ?
  """;
    final String insertCalendarHolidaySql = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
      values (?, ?, ?, 'HOLIDAY', 'local holiday', 'CALENDAR')\
      """;
    final String insertCalendarHolidaySql2 = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)\s\
      values (?, ?, ?, 'HOLIDAY', 'foreign holiday', 'CALENDAR')\
      """;
    final String deleteCalendarHolidaySql = """
        delete from batch.calendar_holiday where tenant_id = ? and calendar_id = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = 't2' and calendar_code = ?
    """;

    String foreignCalendar = "CAL_HOLIDAY_FOREIGN_" + System.nanoTime();
    insertCalendar("CAL_HOLIDAY_LOCAL", "Asia/Shanghai");
    Long localCalendarId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, "CAL_HOLIDAY_LOCAL");
    jdbcTemplate.update(insertBusinessCalendarSql, foreignCalendar);
    Long foreignCalendarId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, foreignCalendar);

    try {
      jdbcTemplate.update(insertCalendarHolidaySql, TENANT, localCalendarId, BIZ);

      assertThatThrownBy(() -> jdbcTemplate.update(
              insertCalendarHolidaySql2, TENANT, foreignCalendarId, BIZ.plusDays(1)))
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbcTemplate.update(deleteCalendarHolidaySql, TENANT, foreignCalendarId);
      jdbcTemplate.update(deleteBusinessCalendarSql, foreignCalendar);
    }
  }

  @Test
  @DisplayName("日历组引用必须同租户且 GROUP 假期代表日历必须属于该组")
  void shouldEnforceCalendarGroupTenantAndHolidayRepresentativeReferences() {
    final String insertCalendarGroupSql = """
        insert into batch.calendar_group (tenant_id, group_code) values (?, ?), (?, ?), (?, ?)\
        """;
    final String selectIdSql = """
      select id from batch.business_calendar where tenant_id = ? and calendar_code = ?
  """;
    final String updateBusinessCalendarSql = """
      update batch.business_calendar set group_code = ? where tenant_id = ? and id = ?
  """;
    final String insertCalendarHolidaySql = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope,\s\
      group_code) values (?, ?, ?, 'HOLIDAY', 'valid group holiday', 'GROUP', ?)\
      """;
    final String updateBusinessCalendarSql2 = """
      update batch.business_calendar set group_code = ? where tenant_id = ? and id = ?\
      """;
    final String insertCalendarHolidaySql2 = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope,\s\
      group_code) values (?, ?, ?, 'HOLIDAY', 'foreign group holiday', 'GROUP', ?)\
      """;
    final String insertCalendarHolidaySql3 = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope,\s\
      group_code) values (?, ?, ?, 'HOLIDAY', 'wrong representative', 'GROUP', ?)\
      """;
    final String deleteCalendarGroupSql = """
      delete from batch.calendar_group where tenant_id = ? and group_code = ?
  """;
    final String deleteCalendarHolidaySql = """
      delete from batch.calendar_holiday where tenant_id = ? and (group_code in (?, ?, ?)\s\
      or calendar_id in (select id from batch.business_calendar where tenant_id = ?\s\
      and calendar_code in (?, ?)))\
      """;
    final String deleteBusinessCalendarSql = """
      delete from batch.business_calendar where tenant_id = ? and calendar_code in (?, ?)
  """;
    final String deleteCalendarGroupSql2 = """
      delete from batch.calendar_group where (tenant_id = ? and group_code in (?, ?)) or (tenant_id = 't2'\s\
      and group_code = ?)\
      """;

    String localGroup = "CAL_GROUP_LOCAL_" + System.nanoTime();
    String otherGroup = "CAL_GROUP_OTHER_" + System.nanoTime();
    String foreignGroup = "CAL_GROUP_FOREIGN_" + System.nanoTime();
    String localCalendar = "CAL_GROUP_LOCAL_" + System.nanoTime();
    String otherCalendar = "CAL_GROUP_OTHER_" + System.nanoTime();
    try {
      jdbcTemplate.update(
          insertCalendarGroupSql, TENANT, localGroup, TENANT, otherGroup, "t2", foreignGroup);
      insertCalendar(localCalendar, "Asia/Shanghai");
      insertCalendar(otherCalendar, "Asia/Shanghai");
      Long localCalendarId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, localCalendar);
      Long otherCalendarId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, otherCalendar);
      jdbcTemplate.update(updateBusinessCalendarSql, localGroup, TENANT, localCalendarId);
      jdbcTemplate.update(updateBusinessCalendarSql, otherGroup, TENANT, otherCalendarId);
      jdbcTemplate.update(insertCalendarHolidaySql, TENANT, localCalendarId, BIZ, localGroup);

      assertThatThrownBy(() -> jdbcTemplate.update(
              updateBusinessCalendarSql2, foreignGroup, TENANT, localCalendarId))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> jdbcTemplate.update(
              insertCalendarHolidaySql2, TENANT, localCalendarId, BIZ.plusDays(1), foreignGroup))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> jdbcTemplate.update(
              insertCalendarHolidaySql3, TENANT, otherCalendarId, BIZ.plusDays(2), localGroup))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> jdbcTemplate.update(deleteCalendarGroupSql, TENANT, localGroup))
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbcTemplate.update(
          deleteCalendarHolidaySql,
          TENANT,
          localGroup,
          otherGroup,
          foreignGroup,
          TENANT,
          localCalendar,
          otherCalendar);
      jdbcTemplate.update(deleteBusinessCalendarSql, TENANT, localCalendar, otherCalendar);
      jdbcTemplate.update(deleteCalendarGroupSql2, TENANT, localGroup, otherGroup, foreignGroup);
    }
  }

  @Test
  @DisplayName("数据库拒绝直接更新日历依赖边形成的环")
  void shouldRejectCalendarDependencyCycleCreatedByDirectUpdate() {
    final String updateCalendarDependencySql = """
        update batch.calendar_dependency set upstream_code = ?, downstream_code = ? where tenant_id = ?\s\
        and upstream_code = ? and downstream_code = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.calendar_dependency where tenant_id = ?
    """;

    insertCalendar("CAL_CYCLE_A", "Asia/Shanghai");
    insertCalendar("CAL_CYCLE_B", "Asia/Shanghai");
    insertCalendar("CAL_CYCLE_C", "Asia/Shanghai");
    insertCalendar("CAL_CYCLE_D", "Asia/Shanghai");
    insertCalendarDependency("CAL_CYCLE_A", "CAL_CYCLE_B");
    insertCalendarDependency("CAL_CYCLE_C", "CAL_CYCLE_D");

    assertTenantWriteRejected(() -> jdbcTemplate.update(
        updateCalendarDependencySql,
        "CAL_CYCLE_B",
        "CAL_CYCLE_A",
        TENANT,
        "CAL_CYCLE_C",
        "CAL_CYCLE_D"));

    Integer selectedCalendarDependencyCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedCalendarDependencyCount).isEqualTo(2);
  }

  @Test
  @DisplayName("数据库串行化同租户日历依赖写入并拒绝并发形成的环")
  void shouldRejectConcurrentCalendarDependencyCycle() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.calendar_dependency where tenant_id = ?\s\
        and upstream_code like 'CAL_CONCURRENT_%%'\
        """;

    insertCalendar("CAL_CONCURRENT_A", "Asia/Shanghai");
    insertCalendar("CAL_CONCURRENT_B", "Asia/Shanghai");
    CyclicBarrier bothTransactionsReady = new CyclicBarrier(2);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> aToB = executor.submit(() -> insertCalendarDependencyConcurrently(
          transaction, "CAL_CONCURRENT_A", "CAL_CONCURRENT_B", bothTransactionsReady));
      Future<Boolean> bToA = executor.submit(() -> insertCalendarDependencyConcurrently(
          transaction, "CAL_CONCURRENT_B", "CAL_CONCURRENT_A", bothTransactionsReady));

      assertThat(aToB.get()).isNotEqualTo(bToA.get());
    }

    Integer selectedCalendarDependencyCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedCalendarDependencyCount).isEqualTo(1);
  }

  private boolean insertCalendarDependencyConcurrently(
      TransactionTemplate transaction,
      String upstream,
      String downstream,
      CyclicBarrier bothTransactionsReady)
      throws Exception {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, ?, ?, 'WAIT_SETTLED', true)\
        """;

    try {
      return Boolean.TRUE.equals(transaction.execute(status -> {
        try {
          bothTransactionsReady.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
          throw new IllegalStateException(
              "calendar dependency writers did not synchronize", failure);
        }
        return jdbcTemplate.update(insertCalendarDependencySql, TENANT, upstream, downstream) == 1;
      }));
    } catch (DataIntegrityViolationException rejectedCycle) {
      return false;
    }
  }

  private void assertTenantWriteRejected(Runnable write) {
    assertThatThrownBy(write::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insertCalendarDependency(String upstream, String downstream) {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, ?, ?, 'WAIT_SETTLED', true)\
        """;

    jdbcTemplate.update(insertCalendarDependencySql, TENANT, upstream, downstream);
  }

  @Test
  @DisplayName("上游跨过下游 cutoff 才结算时,重检仍恢复首次等待的原业务日")
  void shouldReconcileBlockedBusinessDateAfterCutoff() {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)\
        """;
    final String selectWaitStatusSql = """
      select wait_status, biz_date from batch.calendar_dependency_wait where tenant_id = ?\s\
      and calendar_code = 'CAL_HK' and biz_date = ?\
      """;
    final String updateBatchDayInstanceSql = """
      update batch.batch_day_instance set day_status = 'SETTLED', settled_at = current_timestamp\s\
      where tenant_id = ? and calendar_code = 'CAL_CN' and biz_date = ?\
      """;
    final String selectDayStatusSql = """
      select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_HK'\s\
      and biz_date = ?\
      """;
    final String selectWaitStatusSql2 = """
      select wait_status from batch.calendar_dependency_wait where tenant_id = ? and calendar_code = 'CAL_HK'\s\
      and biz_date = ?\
      """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_instance where tenant_id = ? and calendar_code = 'CAL_HK'\s\
        and biz_date = ?\
        """;

    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Shanghai");
    jdbcTemplate.update(insertCalendarDependencySql, TENANT);
    Instant firstOpen = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();

    scheduler.openDueBatchDays(firstOpen);

    Map<String, Object> waitBefore = jdbcTemplate.queryForMap(selectWaitStatusSql, TENANT, BIZ);
    assertThat(waitBefore.get("wait_status")).isEqualTo("WAITING");
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> assertThat(calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
                TENANT, "CAL_HK", BIZ))
            .isEqualTo(1));
    jdbcTemplate.update(updateBatchDayInstanceSql, TENANT, BIZ);

    Instant afterCutoff =
        BIZ.plusDays(1).atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    scheduler.openDueBatchDays(afterCutoff);

    Map<String, Object> recoveredDay = jdbcTemplate.queryForMap(selectDayStatusSql, TENANT, BIZ);
    assertThat(recoveredDay.get("day_status")).isEqualTo("OPEN");
    Map<String, Object> waitAfter = jdbcTemplate.queryForMap(selectWaitStatusSql2, TENANT, BIZ);
    assertThat(waitAfter.get("wait_status")).isEqualTo("OPENED");
    RlsTenantContextHolder.runWithTenant(
        TENANT,
        () -> assertThat(calendarDependencyWaitMapper.countWaitingByTenantCalendarBizDate(
                TENANT, "CAL_HK", BIZ))
            .isZero());
    scheduler.openDueBatchDays(afterCutoff);
    Integer recoveredRows = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, BIZ);
    assertThat(recoveredRows).isEqualTo(1);
  }

  @Test
  @DisplayName("多实例同时扫描时依赖等待按唯一键收敛为一条记录")
  void shouldDeduplicateConcurrentDependencyWaits() throws Exception {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, 'CAL_CN', 'CAL_HK', 'WAIT_SETTLED', true)\
        """;
    final String selectCountSql = """
        select count(*) from batch.calendar_dependency_wait where tenant_id = ? and calendar_code = 'CAL_HK'\s\
        and biz_date = ?\
        """;

    insertCalendar("CAL_CN", "Asia/Shanghai");
    insertCalendar("CAL_HK", "Asia/Shanghai");
    jdbcTemplate.update(insertCalendarDependencySql, TENANT);
    Instant now = BIZ.atTime(23, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();

    CompletableFuture<Void> first =
        CompletableFuture.runAsync(() -> scheduler.openDueBatchDays(now));
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(() -> scheduler.openDueBatchDays(now));
    CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

    Integer waitRows = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, BIZ);
    assertThat(waitRows).isEqualTo(1);
  }

  @Test
  @DisplayName("依赖等待超时后只发一次告警且保持 WAITING 供后续自动恢复")
  void shouldClaimDependencyWaitTimeoutAlertOnce() {
    final String insertCalendarDependencySql = """
        insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
        values (?, 'CAL_DEP_PENDING', 'CAL_TIMEOUT', 'WAIT_SETTLED', true)\
        """;
    final String updateCalendarDependencyWaitSql = """
        update batch.calendar_dependency_wait set first_waiting_at = current_timestamp - interval '2 days'\s\
        where tenant_id = ? and calendar_code = 'CAL_TIMEOUT' and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.alert_event where tenant_id = ?\s\
        and alert_type = 'BATCH_CALENDAR_DEPENDENCY_WAIT_TIMEOUT'\
        """;
    final String selectWaitStatusSql = """
        select wait_status, timeout_alerted_at from batch.calendar_dependency_wait where tenant_id = ?\s\
        and calendar_code = 'CAL_TIMEOUT' and biz_date = ?\
        """;

    insertCalendar("CAL_DEP_PENDING", "Asia/Shanghai");
    insertCalendar("CAL_TIMEOUT", "Asia/Shanghai");
    jdbcTemplate.update(insertCalendarDependencySql, TENANT);
    Instant now = Instant.now();
    scheduler.openDueBatchDays(now);
    var localNow = now.atZone(ZoneId.of("Asia/Shanghai"));
    LocalDate bizDate = localNow.toLocalTime().isBefore(LocalTime.of(22, 0))
        ? localNow.toLocalDate().minusDays(1)
        : localNow.toLocalDate();
    jdbcTemplate.update(updateCalendarDependencyWaitSql, TENANT, bizDate);

    scheduler.openDueBatchDays(now);
    scheduler.openDueBatchDays(now.plusSeconds(1));

    Integer alertRows = jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    Map<String, Object> wait = jdbcTemplate.queryForMap(selectWaitStatusSql, TENANT, bizDate);
    assertThat(alertRows).isEqualTo(1);
    assertThat(wait.get("wait_status")).isEqualTo("WAITING");
    assertThat(wait.get("timeout_alerted_at")).isNotNull();
  }

  @Test
  @DisplayName("Console 人工 RELEASE 和 SKIP 请求经 Orchestrator 调度器审计落实")
  void shouldApplyAuditedManualCalendarDependencyActions() {
    final String insertCalendarDependencySql = """
      insert into batch.calendar_dependency (tenant_id, upstream_code, downstream_code, rule, enabled)\s\
      values (?, 'cal_dep_pending', 'cal_manual_release', 'WAIT_SETTLED', true), (?, 'cal_dep_pending',\s\
      'cal_manual_skip', 'WAIT_SETTLED', true)\
      """;
    final String selectDayStatusSql = """
      select day_status, operated_by, operation_reason from batch.batch_day_instance where tenant_id = ?\s\
      and calendar_code = 'cal_manual_release' and biz_date = ?\
      """;
    final String selectDayStatusSql2 = """
      select day_status, operated_by, operation_reason from batch.batch_day_instance where tenant_id = ?\s\
      and calendar_code = 'cal_manual_skip' and biz_date = ?\
      """;

    insertCalendar("cal_dep_pending", "Asia/Shanghai");
    insertCalendar("cal_manual_release", "Asia/Shanghai");
    insertCalendar("cal_manual_skip", "Asia/Shanghai");
    jdbcTemplate.update(insertCalendarDependencySql, TENANT, TENANT);
    Instant now = Instant.now();
    var localNow = now.atZone(ZoneId.of("Asia/Shanghai"));
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

    Map<String, Object> releasedDay = jdbcTemplate.queryForMap(selectDayStatusSql, TENANT, bizDate);
    assertThat(releasedDay.get("day_status")).isEqualTo("OPEN");
    assertThat(releasedDay.get("operated_by")).isEqualTo("ops-user");
    assertThat(releasedDay.get("operation_reason").toString())
        .contains("MANUAL_CALENDAR_DEPENDENCY_RELEASE:upstream approved");
    Map<String, Object> skippedDay = jdbcTemplate.queryForMap(selectDayStatusSql2, TENANT, bizDate);
    assertThat(skippedDay.get("day_status")).isEqualTo("SKIPPED");
    assertThat(skippedDay.get("operated_by")).isEqualTo("ops-user");
    assertThat(skippedDay.get("operation_reason").toString())
        .contains("MANUAL_CALENDAR_DEPENDENCY_SKIP:source day cancelled");
  }

  @Test
  @DisplayName("文件到达 sensor 可按业务日、到达组和完整组状态过滤同名文件")
  void shouldMatchOnlyTriggeredFileGroupForRequestedBusinessDate() {
    final String updateFileRecordSql = """
        update batch.file_record set metadata_json = jsonb_set(metadata_json, '{arrivalState}', '"TRIGGERED"')\s\
        where tenant_id = ? and file_name = ?\
        """;
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id = ? and file_name like ?
    """;

    String filePrefix = "sensor-settle-" + System.nanoTime();
    String currentFile = filePrefix + "-current.csv";
    String oldFile = filePrefix + "-old.csv";
    String groupCode = "SENSOR_" + System.nanoTime();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime since = now.minusDays(1);
    try {
      insertSensorFile(oldFile, BIZ.minusDays(1), groupCode, "TRIGGERED", now.minusHours(2));
      insertSensorFile(currentFile, BIZ, groupCode, "WAITING_ARRIVAL", now.minusHours(1));
      SensorFileArrivalView incomplete = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              SensorFileArrivalLatestArrivalQuery.builder()
                  .tenantId(TENANT)
                  .namePattern(filePrefix + "%.csv")
                  .sourceType("SFTP")
                  .since(since)
                  .bizDate(BIZ)
                  .fileGroupCode(groupCode)
                  .workflowDefinitionId(null)
                  .requireCompleteGroup(true)
                  .build()));
      assertThat(incomplete).isNull();

      jdbcTemplate.update(updateFileRecordSql, TENANT, currentFile);
      SensorFileArrivalView matched = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              SensorFileArrivalLatestArrivalQuery.builder()
                  .tenantId(TENANT)
                  .namePattern(filePrefix + "%.csv")
                  .sourceType("SFTP")
                  .since(since)
                  .bizDate(BIZ)
                  .fileGroupCode(groupCode)
                  .workflowDefinitionId(null)
                  .requireCompleteGroup(true)
                  .build()));
      assertThat(matched.fileName()).isEqualTo(currentFile);
      assertThat(matched.bizDate().toString()).isEqualTo(BIZ.toString());
    } finally {
      jdbcTemplate.update(deleteFileRecordSql, TENANT, filePrefix + "%");
    }
  }

  @Test
  @DisplayName("完整文件组 sensor 不会把其它目标 Workflow 的同组到达误判为命中")
  void shouldNotMatchBundleFileForDifferentTargetWorkflow() {
    final String updateFileRecordSql = """
        update batch.file_record set metadata_json = jsonb_set(metadata_json, '{bundleWorkflowCode}',\s\
        to_jsonb(?::text), true) where tenant_id = ? and file_name = ?\
        """;
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id = ? and file_name = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id in (?, ?)
    """;

    String suffix = Long.toString(System.nanoTime());
    String groupCode = "SENSOR_TARGET_" + suffix;
    String fileName = "sensor-target-" + suffix + ".csv";
    String sourceWorkflowCode = "WF_FILE_SOURCE_" + suffix;
    String waitingWorkflowCode = "WF_FILE_WAITING_" + suffix;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime since = now.minusHours(1);
    Long sourceWorkflowId = insertSensorWorkflow(sourceWorkflowCode);
    Long waitingWorkflowId = insertSensorWorkflow(waitingWorkflowCode);
    try {
      insertSensorFile(fileName, BIZ, groupCode, "TRIGGERED", now);
      jdbcTemplate.update(updateFileRecordSql, sourceWorkflowCode, TENANT, fileName);

      SensorFileArrivalView wrongTarget = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              SensorFileArrivalLatestArrivalQuery.builder()
                  .tenantId(TENANT)
                  .namePattern("sensor-target-%.csv")
                  .sourceType("SFTP")
                  .since(since)
                  .bizDate(BIZ)
                  .fileGroupCode(groupCode)
                  .workflowDefinitionId(waitingWorkflowId)
                  .requireCompleteGroup(true)
                  .build()));
      assertThat(wrongTarget).isNull();

      SensorFileArrivalView matchingTarget = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> sensorFileArrivalMapper.selectLatestArrival(
              SensorFileArrivalLatestArrivalQuery.builder()
                  .tenantId(TENANT)
                  .namePattern("sensor-target-%.csv")
                  .sourceType("SFTP")
                  .since(since)
                  .bizDate(BIZ)
                  .fileGroupCode(groupCode)
                  .workflowDefinitionId(sourceWorkflowId)
                  .requireCompleteGroup(true)
                  .build()));
      assertThat(matchingTarget.fileName()).isEqualTo(fileName);
    } finally {
      jdbcTemplate.update(deleteFileRecordSql, TENANT, fileName);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, TENANT, sourceWorkflowId, waitingWorkflowId);
    }
  }

  private Long insertSensorWorkflow(String workflowCode) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, 'JOB_CALENDAR') returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, workflowCode, workflowCode);
  }

  private void insertSensorFile(
      String fileName,
      LocalDate bizDate,
      String groupCode,
      String arrivalState,
      OffsetDateTime createdAt) {
    final String insertFileRecordSql = """
            insert into batch.file_record (tenant_id, file_category, file_name, file_format_type, storage_type,\s\
            storage_path, source_type, file_status, biz_date, metadata_json, created_at)\s\
            values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,\s\
            jsonb_build_object('fileGroupCode', ?, 'arrivalState', ?), ?)\
            """;

    jdbcTemplate.update(
        insertFileRecordSql,
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
    final String savepointBeforeWaitRlsViolationSql = "SAVEPOINT before_wait_rls_violation";
    final String savepointBeforeCursorRlsViolationSql = "SAVEPOINT before_cursor_rls_violation";

    final String selectCountSql = """
        SELECT count(*) FROM batch.calendar_dependency_wait WHERE calendar_code = 'CAL_RLS'
    """;
    final String selectCountSql2 = """
        SELECT count(*) FROM batch.batch_day_open_cursor WHERE calendar_code = 'CAL_RLS'
    """;
    final String insertCalendarDependencyWaitSql2 = """
        INSERT INTO batch.calendar_dependency_wait (tenant_id, calendar_code, biz_date, blocked_reason,\s\
        first_waiting_at, last_checked_at, timezone_snapshot, dst_policy_snapshot)\s\
        VALUES ('t2', 'CAL_RLS', DATE '2026-05-08', 'blocked', current_timestamp, current_timestamp,\s\
        'Asia/Shanghai', 'EARLIER_OFFSET')\
        """;
    final String insertBatchDayOpenCursorSql2 = """
        INSERT INTO batch.batch_day_open_cursor (tenant_id, calendar_code, last_scanned_biz_date)\s\
        VALUES ('t2', 'CAL_RLS', DATE '2026-05-09')\
        """;

    final String rollbackToSql = "ROLLBACK TO SAVEPOINT before_wait_rls_violation";
    final String rollbackToSql2 = "ROLLBACK TO SAVEPOINT before_cursor_rls_violation";

    final String insertCalendarDependencyWaitSql = """
        insert into batch.calendar_dependency_wait (tenant_id, calendar_code, biz_date, wait_status,\s\
        blocked_reason, first_waiting_at, last_checked_at, timezone_snapshot, dst_policy_snapshot)\s\
        values (?, 'CAL_RLS', ?, 'WAITING', 'blocked', current_timestamp, current_timestamp, 'Asia/Shanghai',\s\
        'EARLIER_OFFSET')\
        """;
    final String insertBatchDayOpenCursorSql = """
        insert into batch.batch_day_open_cursor (tenant_id, calendar_code, last_scanned_biz_date)\s\
        values (?, 'CAL_RLS', ?)\
        """;

    String role = "calendar_dependency_wait_rls_test";
    final String dropRoleSql = """
        DROP ROLE IF EXISTS %s
    """.formatted(role);
    jdbcTemplate.execute(dropRoleSql);
    final String createRoleSql = """
        CREATE ROLE %s NOLOGIN
    """.formatted(role);
    jdbcTemplate.execute(createRoleSql);
    try {
      final String grantUsageSql = """
          GRANT USAGE ON SCHEMA batch TO %s
      """.formatted(role);
      jdbcTemplate.execute(grantUsageSql);
      final String grantSelectSql = """
          GRANT SELECT, INSERT ON batch.calendar_dependency_wait TO %s
      """.formatted(role);
      jdbcTemplate.execute(grantSelectSql);
      final String grantBatchDayCursorSql = """
          GRANT SELECT, INSERT ON batch.batch_day_open_cursor TO %s
      """.formatted(role);
      jdbcTemplate.execute(grantBatchDayCursorSql);
      final String grantWaitSequenceUsageSql = """
          GRANT USAGE, SELECT ON SEQUENCE batch.calendar_dependency_wait_id_seq TO %s
          """.formatted(role);
      jdbcTemplate.execute(grantWaitSequenceUsageSql);
      insertCalendar("CAL_RLS", "Asia/Shanghai");
      jdbcTemplate.update(insertCalendarDependencyWaitSql, TENANT, BIZ);
      jdbcTemplate.update(insertCalendarDependencyWaitSql, "t2", BIZ);
      jdbcTemplate.update(insertBatchDayOpenCursorSql, TENANT, BIZ);
      jdbcTemplate.update(insertBatchDayOpenCursorSql, "t2", BIZ);

      jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          final String setRoleSql = """
              SET ROLE %s\
              """.formatted(role);
          statement.execute(setRoleSql);
          final String selectSetConfigSql = """
              SELECT set_config('app.tenant_id', '%s', true)
              """.formatted(TENANT);
          statement.execute(selectSetConfigSql);
          try (var result = statement.executeQuery(selectCountSql)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1L);
          }
          try (var result = statement.executeQuery(selectCountSql2)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1L);
          }
          statement.execute(savepointBeforeWaitRlsViolationSql);
          assertThatThrownBy(() -> statement.executeUpdate(insertCalendarDependencyWaitSql2))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");
          statement.execute(rollbackToSql);
          statement.execute(savepointBeforeCursorRlsViolationSql);
          assertThatThrownBy(() -> statement.executeUpdate(insertBatchDayOpenCursorSql2))
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");
          statement.execute(rollbackToSql2);
        } finally {
          connection.rollback();
          connection.setAutoCommit(true);
        }
        return null;
      });
    } finally {
      final String dropOwnedSql = """
          DROP OWNED BY %s
      """.formatted(role);
      jdbcTemplate.execute(dropOwnedSql);
      final String dropRoleAfterCleanupSql = """
          DROP ROLE %s
      """.formatted(role);
      jdbcTemplate.execute(dropRoleAfterCleanupSql);
    }
  }

  @Test
  @DisplayName("灾害日跳过覆盖生效时批次日进入跳过或延期,而不是正常开启")
  void shouldSkipBatchDayOpen_whenDisasterOverrideActive() {
    final String insertDisasterDayOverrideSql = """
        insert into batch.disaster_day_override (tenant_id, calendar_code, biz_date, action, reason,\s\
        approved_by,  approved_at, effective_at, ttl_until)\s\
        values (?, 'CAL_US', ?, 'SKIP', 'hurricane', 'ops-1', ?, ?, ?)\
        """;

    insertCalendar("CAL_US", "America/New_York");
    // NY EDT cutoff=22:00 → 用 NY 本地 22:01 of BIZ 让 scheduler 算出 bizDate=BIZ
    Instant now = BIZ.atTime(22, 1).atZone(ZoneId.of("America/New_York")).toInstant();
    // disaster_day_override.effective_at 默认 = current_timestamp(墙钟今天),
    // 而测试 now = BIZ(可能是几天前)。selectActiveByCalendarBizDate 的
    // "effective_at <= now" 过滤会把这条 override 过滤掉。显式把 effective_at
    // 设为 now - 1 小时,保证 mapper 能命中。
    jdbcTemplate.update(
        insertDisasterDayOverrideSql,
        TENANT,
        BIZ,
        Timestamp.from(now.minusSeconds(3600)),
        Timestamp.from(now.minusSeconds(3600)),
        Timestamp.from(now.plusSeconds(86400)));
    scheduler.openDueBatchDays(now);
    Map<String, Object> usRow = selectFirstBatchDay("CAL_US");

    // 灾难 SKIP 触发后，batch_day_instance 进 SKIPPED 状态（看 BatchDayOpenScheduler.handleDisasterOverride）
    assertThat(usRow).isNotNull();
    assertThat(usRow.get("day_status")).isIn("SKIPPED", "DEFERRED");
  }

  @Test
  @DisplayName("按工作日差异化的截断时刻配置以结构化列落库并可完整读回")
  void shouldPersistCutoffSchedule_whenJsonbColumnUsed() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, \s\
        enabled, cutoff_schedule) values (?, 'CAL_JP', 'JP', 'Asia/Tokyo', '22:00', true, ?::jsonb)\
        """;
    final String selectCalendarCodeSql = """
        select calendar_code, cutoff_schedule::text as cutoff_schedule from batch.business_calendar\s\
        where tenant_id = ? and calendar_code = 'CAL_JP'\
        """;

    String cutoffJson = "{\"default\":\"22:00\",\"weekdayPattern\":{\"THURSDAY\":\"12:00\"}}";
    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, cutoffJson);

    Map<String, Object> row = jdbcTemplate.queryForMap(selectCalendarCodeSql, TENANT);

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
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        holiday_roll_rule, enabled) values (?, ?, ?, ?, '22:00', ?, true)\
        """;

    jdbcTemplate.update(insertBusinessCalendarSql, TENANT, code, code, timezone, holidayRollRule);
  }

  private Map<String, Object> selectFirstBatchDay(String calendarCode) {
    final String selectStatementSql = """
        select * from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        order by biz_date desc, id desc limit 1\
        """;

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(selectStatementSql, TENANT, calendarCode);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
