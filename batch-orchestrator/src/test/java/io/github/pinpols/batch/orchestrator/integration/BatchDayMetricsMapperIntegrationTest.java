package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExpectedWorkRegistrationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("批量日实例指标与终态门禁查询,校验真实数据库上的计数口径与批量日写入默认值")
class BatchDayMetricsMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final JobInstanceMapper mapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final JobDependencyMapper jobDependencyMapper;
  private final ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private final ExpectedWorkRegistrationService expectedWorkRegistrationService;
  private final FileGroupExpectationService fileGroupExpectationService;
  private final ExternalEventExpectationService externalEventExpectationService;
  private final TriggerRequestMapper triggerRequestMapper;

  @Autowired
  BatchDayMetricsMapperIntegrationTest(
      JdbcTemplate jdbcTemplate,
      JobInstanceMapper mapper,
      BatchDayInstanceMapper batchDayInstanceMapper,
      BatchDayPlanCalendarMapper planCalendarMapper,
      JobDependencyMapper jobDependencyMapper,
      ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper,
      ExpectedWorkRegistrationService expectedWorkRegistrationService,
      FileGroupExpectationService fileGroupExpectationService,
      ExternalEventExpectationService externalEventExpectationService,
      TriggerRequestMapper triggerRequestMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
    this.planCalendarMapper = planCalendarMapper;
    this.jobDependencyMapper = jobDependencyMapper;
    this.expectedWorkOccurrenceMapper = expectedWorkOccurrenceMapper;
    this.expectedWorkRegistrationService = expectedWorkRegistrationService;
    this.fileGroupExpectationService = fileGroupExpectationService;
    this.externalEventExpectationService = externalEventExpectationService;
    this.triggerRequestMapper = triggerRequestMapper;
  }

  @Test
  @DisplayName("汇总指标时排除试运行实例并把暂停计入活跃,总数与成功失败活跃计数分别符合预期")
  void shouldExcludeDryRunAndCountPausedAsActive_whenAggregatingMetrics() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    String jobCode = unique("job");
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    BatchDayInstanceMetrics metrics = mapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);

    assertThat(metrics.getTotalCount()).isEqualTo(3);
    assertThat(metrics.getSuccessCount()).isEqualTo(1);
    assertThat(metrics.getFailedCount()).isEqualTo(1);
    assertThat(metrics.getActiveCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("门禁把部分失败与试运行等已终结状态都算作完成,只有暂停实例会同时抬高作业与分组的未终结计数")
  void shouldTreatEveryTerminalStatusAsComplete_whenCountingNonTerminal() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("gate-job");
    String jobGroupCode = unique("group");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode, jobGroupCode);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PARTIAL_FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isZero();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isZero();

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isOne();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isOne();
  }

  @Test
  @DisplayName("空日门禁把尚未派发的 Quartz 计划 occurrence 作为待处理工作")
  void shouldCountPlannedScheduledOccurrenceAsBlockingEmptySettlement() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenantId,
        tenantId);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        "insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name)"
            + " values (?, ?, ?) returning id",
        Long.class,
        tenantId,
        unique("scheduled-workflow"),
        "Scheduled occurrence fixture");
    jdbcTemplate.update(
        "insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,"
            + " schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)"
            + " values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')",
        tenantId,
        workflowDefinitionId,
        unique("scheduled-workflow"),
        calendarCode,
        java.sql.Timestamp.from(Instant.parse("2026-09-07T09:00:00Z")),
        bizDate);

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("自动空日结算必须对未建立预期清单的 EVENT 与 MIXED 作业失败关闭")
  void shouldCountEnabledEventAndMixedJobsAsUntrackedEmptyDayExpectations() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    insertJobDefinition(tenantId, calendarCode, unique("event-job"), "EVENT");
    insertJobDefinition(tenantId, calendarCode, unique("mixed-job"), "MIXED");
    insertJobDefinition(tenantId, calendarCode, unique("scheduled-job"), "SCHEDULED");
    jdbcTemplate.update(
        "update batch.job_definition set trigger_mode = 'EVENT', enabled = true"
            + " where tenant_id = ? and job_code like 'event-job-%'",
        tenantId);
    jdbcTemplate.update(
        "update batch.job_definition set trigger_mode = 'MIXED', enabled = true"
            + " where tenant_id = ? and job_code like 'mixed-job-%'",
        tenantId);

    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("空日门禁只统计指定日历业务日尚未终结的显式事件/文件预期项")
  void shouldCountOpenExpectedWorkForExactCalendarAndBizDate() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String fileGroup = unique("file-group");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    assertThat(expectedWorkOccurrenceMapper.insertIfAbsent(
            tenantId, calendarCode, bizDate, jobCode, "FILE_GROUP", fileGroup, null, null))
        .isEqualTo(1);
    assertThat(expectedWorkOccurrenceMapper.insertIfAbsent(
            tenantId, calendarCode, bizDate, jobCode, "FILE_GROUP", fileGroup, null, null))
        .isZero();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.transitionStatus(
            tenantId,
            calendarCode,
            jobCode,
            "FILE_GROUP",
            fileGroup,
            bizDate,
            "EXPECTED",
            "ARRIVED",
            null))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.transitionStatus(
            tenantId,
            calendarCode,
            jobCode,
            "FILE_GROUP",
            fileGroup,
            bizDate,
            "ARRIVED",
            "SATISFIED",
            "request-1"))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
  }

  @Test
  @DisplayName("到达前登记入口在真实数据库中验证作业日历并使未到文件阻塞空日")
  void shouldRegisterPreArrivalExpectationAndBlockEmptySettlement() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("file-job");
    String fileGroupCode = unique("expected-file-group");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    insertJobDefinition(tenantId, calendarCode, jobCode, null);

    var request = new ExpectedWorkRegistrationService.FileGroupExpectation(
        tenantId,
        calendarCode,
        jobCode,
        fileGroupCode,
        bizDate,
        Instant.parse("2026-10-14T01:00:00Z"),
        Instant.parse("2026-10-14T03:00:00Z"));
    assertThat(expectedWorkRegistrationService.register(request).inserted()).isTrue();
    assertThat(expectedWorkRegistrationService.register(request).inserted()).isFalse();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThatThrownBy(() -> expectedWorkRegistrationService.register(
            new ExpectedWorkRegistrationService.FileGroupExpectation(
                tenantId,
                unique("wrong-calendar"),
                jobCode,
                unique("wrong-group"),
                bizDate,
                Instant.parse("2026-10-14T01:00:00Z"),
                null)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  @DisplayName("开启业务日时按已配置文件组规则幂等生成当天预期项")
  void shouldMaterializeRecurringFileExpectationsForOpenedBusinessDay() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("file-job");
    String fileGroupCode = unique("daily-group");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    insertJobDefinition(tenantId, calendarCode, jobCode, null);
    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        jobCode,
        fileGroupCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));

    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isEqualTo(1);
    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isZero();
    Map<String, Object> expected = jdbcTemplate.queryForMap(
        "select expected_at, due_at, occurrence_status from batch.expected_work_occurrence"
            + " where tenant_id = ? and calendar_code = ? and biz_date = ? and job_code = ?"
            + " and source_type = 'FILE_GROUP' and source_key = ?",
        tenantId,
        calendarCode,
        bizDate,
        jobCode,
        fileGroupCode);
    assertThat(expected)
        .containsEntry(
            "expected_at", java.sql.Timestamp.from(Instant.parse("2026-10-14T01:15:00Z")))
        .containsEntry("due_at", java.sql.Timestamp.from(Instant.parse("2026-10-14T02:00:00Z")))
        .containsEntry("occurrence_status", "EXPECTED");
  }

  @Test
  @DisplayName("开启业务日时生成每日事件预期，只有关联事件请求已启动后才能结清")
  void shouldMaterializeAndSatisfyRecurringEventExpectationAfterLaunch() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-job");
    String eventCode = unique("daily-event");
    String requestId = unique("request");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    insertJobDefinition(tenantId, calendarCode, jobCode, null);
    jdbcTemplate.update(
        "update batch.job_definition set trigger_mode = 'EVENT' where tenant_id = ? and job_code = ?",
        tenantId,
        jobCode);
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isOne();
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        jobCode,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();

    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isEqualTo(1);
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isZero();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();

    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId(requestId);
    request.setTriggerType("EVENT");
    request.setExternalEventCode(eventCode);
    request.setJobCode(jobCode);
    request.setBizDate(bizDate);
    request.setDedupKey(unique("dedup"));
    request.setRequestStatus("ACCEPTED");
    request.setTraceId(unique("trace"));
    triggerRequestMapper.insert(request);
    assertThatThrownBy(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, jobCode, eventCode, bizDate, requestId)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

    triggerRequestMapper.updateAcceptance(tenantId, requestId, "LAUNCHED", 101L);
    assertThatThrownBy(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, jobCode, unique("other-event"), bizDate, requestId)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, jobCode, eventCode, bizDate, requestId)))
        .isTrue();
    assertThat(externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, jobCode, eventCode, bizDate, requestId)))
        .isFalse();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();
  }

  @Test
  @DisplayName("EVENT 作业使用逐日文件组预期时不再被当作无跟踪定义")
  void shouldTreatRecurringFileGroupAsTrackingForEventJob() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-file-job");
    insertJobDefinition(tenantId, calendarCode, jobCode, null);
    jdbcTemplate.update(
        "update batch.job_definition set trigger_mode = 'EVENT' where tenant_id = ? and job_code = ?",
        tenantId,
        jobCode);
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isOne();

    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        jobCode,
        unique("daily-group"),
        LocalTime.of(9, 0),
        LocalTime.of(10, 0),
        true));
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();
  }

  @Test
  @DisplayName("前一业务日查询跳过节假日并接受周末工作日覆盖")
  void shouldResolveCalendarAwarePreviousBusinessDate() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("downstream");
    LocalDate monday = LocalDate.of(2026, 10, 12);
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)"
            + " values (?, ?, ?, 'Asia/Shanghai')",
        tenantId,
        calendarCode,
        calendarCode);
    Long calendarId = jdbcTemplate.queryForObject(
        "select id from batch.business_calendar where tenant_id = ? and calendar_code = ?",
        Long.class,
        tenantId,
        calendarCode);
    insertJobDefinition(tenantId, calendarCode, jobCode, null);
    jdbcTemplate.update(
        "insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)"
            + " values (?, ?, ?, 'HOLIDAY', 'CALENDAR')",
        tenantId,
        calendarId,
        LocalDate.of(2026, 10, 9));
    String overrideCalendarCode = unique("calendar-override");
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)"
            + " values (?, ?, ?, 'Asia/Shanghai')",
        tenantId,
        overrideCalendarCode,
        overrideCalendarCode);
    Long overrideCalendarId = jdbcTemplate.queryForObject(
        "select id from batch.business_calendar where tenant_id = ? and calendar_code = ?",
        Long.class,
        tenantId,
        overrideCalendarCode);
    LocalDate workdayOverride = LocalDate.of(2026, 10, 10);
    jdbcTemplate.update(
        "insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)"
            + " values (?, ?, ?, 'WORKDAY_OVERRIDE', 'CALENDAR')",
        tenantId,
        overrideCalendarId,
        workdayOverride);

    assertThat(jobDependencyMapper.selectCalendarCodeByTenantAndJob(tenantId, jobCode))
        .isEqualTo(calendarCode);
    assertThat(planCalendarMapper.selectPreviousBusinessDate(tenantId, calendarCode, monday))
        .isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(
            planCalendarMapper.selectPreviousBusinessDate(tenantId, overrideCalendarCode, monday))
        .isEqualTo(workdayOverride);
  }

  @Test
  @DisplayName("空日结算只接受当前定义版本已覆盖至 cutoff 的所有启用定时作业")
  void shouldRequireCurrentScheduleCoverageThroughCutoff() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("scheduled-job");
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenantId,
        tenantId);
    Long definitionId =
        jdbcTemplate.queryForObject("""
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr, timezone,
          calendar_code, trigger_mode, version, enabled
        ) values (?, ?, 'Coverage Test', 'GENERAL', 'CRON', '0 0 2 * * ?',
          'Asia/Shanghai', ?, 'SCHEDULED', 7, true)
        returning id
        """, Long.class, tenantId, jobCode, calendarCode);
    Long workflowDefinitionId =
        jdbcTemplate.queryForObject("""
        insert into batch.workflow_definition(
          tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
          schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled
        ) values (?, ?, 'Coverage Workflow', 'DAG', 7, true, 'CRON', '0 0 2 * * ?',
          'Asia/Shanghai', 'SCHEDULED', ?, true)
        returning id
        """, Long.class, tenantId, "wf-" + jobCode, calendarCode);
    Instant cutoff = Instant.parse("2026-09-08T08:00:00Z");

    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);

    jdbcTemplate.update(
        """
        insert into batch.scheduled_occurrence_coverage(
          tenant_id, workflow_definition_id, schedule_version, calendar_code,
          coverage_from_at, coverage_through_at
        ) values (?, ?, 6, ?, ?, ?)
        """,
        tenantId,
        workflowDefinitionId,
        calendarCode,
        java.sql.Timestamp.from(cutoff.minusSeconds(3600)),
        java.sql.Timestamp.from(cutoff.plusSeconds(1)));
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);

    jdbcTemplate.update(
        "update batch.scheduled_occurrence_coverage set schedule_version = 7"
            + " where tenant_id = ? and workflow_definition_id = ?",
        tenantId,
        workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isZero();
    jdbcTemplate.update(
        "update batch.scheduled_occurrence_coverage set coverage_from_at = ?"
            + " where tenant_id = ? and workflow_definition_id = ?",
        java.sql.Timestamp.from(cutoff.plusSeconds(1)),
        tenantId,
        workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);
    assertThat(definitionId).isNotNull();
  }

  @Test
  @DisplayName("写入批量日实例时由应用侧提供的状态,计数与快照默认值如实落库")
  void shouldPersistJavaProvidedDefaults_whenInsertingBatchDay() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 9);
    Instant now = Instant.parse("2026-09-09T00:00:00Z");
    BatchDayInstanceEntity entity = BatchDayInstanceEntity.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .dayStatus("OPEN")
        .openAt(now)
        .lateCount(0)
        .catchupCount(0)
        .timezoneSnapshot("Asia/Shanghai")
        .dstPolicySnapshot("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET")
        .frozen(false)
        .version(0L)
        .createdAt(now)
        .updatedAt(now)
        .dryRun(false)
        .build();

    assertThat(batchDayInstanceMapper.insert(entity)).isOne();

    Map<String, Object> stored = jdbcTemplate.queryForMap("""
        select day_status, late_count, catchup_count, timezone_snapshot, frozen, version, dry_run
          from batch.batch_day_instance
         where tenant_id = ? and calendar_code = ? and biz_date = ?
        """, tenantId, calendarCode, bizDate);
    assertThat(stored)
        .containsEntry("day_status", "OPEN")
        .containsEntry("late_count", 0)
        .containsEntry("catchup_count", 0)
        .containsEntry("timezone_snapshot", "Asia/Shanghai")
        .containsEntry("frozen", false)
        .containsEntry("version", 0L)
        .containsEntry("dry_run", false);
  }

  private Long insertJobDefinition(
      String tenantId, String calendarCode, String jobCode, String jobGroupCode) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code,
          job_group_code
        ) values (?, ?, 'Batch Day Metrics Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?, ?)
        returning id
        """, Long.class, tenantId, jobCode, calendarCode, jobGroupCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    jdbcTemplate.update(
        """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, dry_run
        ) values (?, ?, ?, ?, ?, 'MANUAL', ?, 5, ?, 0, 0, 0, ?, ?)
        """,
        tenantId,
        definitionId,
        jobCode,
        unique("instance"),
        bizDate,
        status,
        unique("dedup"),
        unique("trace"),
        dryRun);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
