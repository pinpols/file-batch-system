package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.ops.DefaultConsoleApprovalApplicationService;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFileApplicationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.infrastructure.BatchDayCatchUpOperationStore;
import io.github.pinpols.batch.console.domain.job.infrastructure.DefaultConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.query.BatchDayQuery;
import io.github.pinpols.batch.console.domain.job.view.BatchDayJobSummaryView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayScheduledOccurrenceView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayView;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("BatchDayMapper 批次日汇总查询: 排除 dry-run 实例,暂停态计入在途")
class BatchDayMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final BatchDayMapper mapper;

  private final BusinessCalendarMapper businessCalendars;
  private final JobDefinitionMapper jobDefinitions;
  private final BatchDayCatchUpOperationStore operationStore;

  @Autowired
  BatchDayMapperIntegrationTest(
      JdbcTemplate jdbcTemplate,
      BatchDayMapper mapper,
      BusinessCalendarMapper businessCalendars,
      JobDefinitionMapper jobDefinitions,
      BatchDayCatchUpOperationStore operationStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
    this.businessCalendars = businessCalendars;
    this.jobDefinitions = jobDefinitions;
    this.operationStore = operationStore;
  }

  @Test
  @DisplayName("批次日列表与窗口映射固定统计视图")
  void shouldMapBatchDayListAndWindowToTypedView() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Instant cutoffAt = Instant.parse("2026-09-08T06:00:00Z");
    Instant slaDeadlineAt = Instant.parse("2026-09-08T07:00:00Z");
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    final String insertBatchDaySql = """
        insert into batch.batch_day_instance (
          tenant_id, calendar_code, biz_date, day_status, cutoff_at, settled_at,
          sla_deadline_at, late_count, catchup_count, frozen
        ) values (?, ?, ?, 'SETTLED', ?, ?, ?, 2, 3, true)
        """;
    jdbcTemplate.update(
        insertBatchDaySql,
        tenantId,
        calendarCode,
        bizDate,
        Timestamp.from(cutoffAt),
        Timestamp.from(slaDeadlineAt),
        Timestamp.from(slaDeadlineAt));

    BatchDayView window = mapper.selectWindow(tenantId, calendarCode, bizDate);
    List<BatchDayView> rows = mapper.selectByQuery(BatchDayQuery.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .fromBizDate(bizDate)
        .toBizDate(bizDate)
        .pageRequest(new PageRequest(1, 10))
        .build());

    assertThat(window.bizDate()).isEqualTo(bizDate);
    assertThat(window.dayStatus()).isEqualTo("SETTLED");
    assertThat(window.frozen()).isTrue();
    assertThat(window.cutoffAt()).isEqualTo(cutoffAt);
    assertThat(window.slaDeadlineAt()).isEqualTo(slaDeadlineAt);
    assertThat(window.lateCount()).isEqualTo(2);
    assertThat(window.catchupCount()).isEqualTo(3);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().frozen()).isTrue();
    assertThat(rows.getFirst().totalJobCount()).isEqualTo(1L);
    assertThat(rows.getFirst().successJobCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("汇总只统计真实实例,成功 / 失败 / 在途三个计数各归各口")
  void shouldExcludeDryRunAndCountPausedAsInFlight_whenSummarisingBatchDay() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    List<BatchDayJobSummaryView> rows = mapper.selectJobSummaries(tenantId, calendarCode, bizDate);

    assertThat(rows).hasSize(1);
    BatchDayJobSummaryView summary = rows.getFirst();
    assertThat(summary.totalJobCount()).isEqualTo(3L);
    assertThat(summary.successJobCount()).isEqualTo(1L);
    assertThat(summary.failedJobCount()).isEqualTo(1L);
    assertThat(summary.inFlightJobCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("真实日历关闭补跑时不提交审批或触发")
  void shouldRejectCatchUpForDisabledRealCalendar() {
    final String calendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'NONE')
        """;
    String tenant = unique("tenant");
    String calendar = unique("calendar");
    jdbcTemplate.update(calendarSql, tenant, calendar);
    ConsoleJobOperationsPort operations = mock(ConsoleJobOperationsPort.class);
    when(operations.resolveTenant(tenant)).thenReturn(tenant);
    var service = new DefaultConsoleJobApprovalService(
        operations,
        mock(TriggerInternalRestClient.class),
        mock(ConsoleRequestMetadataResolver.class),
        mapper,
        businessCalendars,
        jobDefinitions,
        mock(ManagedJobWorkflowReferencePort.class),
        operationStore,
        mock(OrchestratorApprovalClient.class));
    BatchDayCatchUpRequest request = new BatchDayCatchUpRequest();
    request.setTenantId(tenant);
    request.setCalendarCode(calendar);
    request.setJobCodes(List.of("JOB"));

    assertThatThrownBy(() -> service.catchUpBatchDay("2026-09-08", request, "idem"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT));
    verify(operations, never()).submitApproval(any());
    verify(operations, never()).delegateLaunch(any());
  }

  @Test
  @DisplayName("人工批日审批经真实服务派发后，其触发快照计入对应批日汇总")
  void shouldCountManualApprovalAfterRealServiceDispatch() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'MANUAL_APPROVAL')
        """;
    final String insertWorkflowSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, single_job_definition_id, calendar_code)
        values (?, ?, 'test', 'DAG', ?, ?) returning id
        """;
    final String insertRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,
            biz_date, dedup_key, request_status, dry_run, launch_payload)
        values (?, ?, 'CATCH_UP', ?, ?, ?, 'LAUNCHED', false, ?::jsonb)
        """;
    String tenant = unique("tenant");
    String calendar = unique("calendar");
    String job = unique("job");
    LocalDate day = LocalDate.of(2026, 9, 8);
    jdbcTemplate.update(insertCalendarSql, tenant, calendar);
    Long definition = insertJobDefinition(tenant, calendar, job);
    insertJobInstance(tenant, definition, job, day, "FAILED", false);
    Long workflow = jdbcTemplate.queryForObject(
        insertWorkflowSql, Long.class, tenant, unique("workflow"), definition, calendar);
    ConsoleJobOperationsPort operations = mock(ConsoleJobOperationsPort.class);
    when(operations.resolveTenant(tenant)).thenReturn(tenant);
    when(operations.hasText(anyString()))
        .thenAnswer(invocation -> !((String) invocation.getArgument(0)).isBlank());
    when(operations.parseBizDate(day.toString())).thenReturn(day);
    AtomicReference<String> approvalPayload = new AtomicReference<>();
    when(operations.submitApproval(any())).thenAnswer(invocation -> {
      ApprovalSubmitContext context = invocation.getArgument(0);
      approvalPayload.set(JsonUtils.toJson(context.payload()));
      return "approval-1";
    });
    ManagedJobWorkflowReferencePort references = mock(ManagedJobWorkflowReferencePort.class);
    when(references.isEnabledSingleJobWorkflow(tenant, workflow, definition)).thenReturn(true);
    when(operations.delegateLaunch(any())).thenAnswer(invocation -> {
      ConsoleLaunchCommand command = invocation.getArgument(0);
      String snapshot = JsonUtils.toJson(Map.of("params", command.params()));
      jdbcTemplate.update(
          insertRequestSql,
          tenant,
          unique("request"),
          command.workflowDefinitionId(),
          day,
          unique("manual"),
          snapshot);
      return "run-1";
    });
    var requestMetadata = mock(ConsoleRequestMetadataResolver.class);
    when(requestMetadata.current())
        .thenReturn(new ConsoleRequestMetadata("req", "trace", tenant, "operator", null, null));
    var jobApproval = new DefaultConsoleJobApprovalService(
        operations,
        mock(TriggerInternalRestClient.class),
        requestMetadata,
        mapper,
        businessCalendars,
        jobDefinitions,
        references,
        operationStore,
        mock(OrchestratorApprovalClient.class));
    BatchDayCatchUpRequest request = new BatchDayCatchUpRequest();
    request.setTenantId(tenant);
    request.setCalendarCode(calendar);
    request.setJobCodes(List.of(job));
    jobApproval.catchUpBatchDay(day.toString(), request, "idem-1");
    assertThat(approvalPayload.get()).isNotNull();
    assertThat(mapper.selectJobSummaries(tenant, calendar, day))
        .singleElement()
        .satisfies(row -> assertThat(row.catchupCount()).isZero());

    OrchestratorInternalRestClient orchestrator = mock(OrchestratorInternalRestClient.class);
    String approvalJson = JsonUtils.toJson(Map.of(
        "record",
        Map.of(
            "tenantId",
            tenant,
            "approvalNo",
            "approval-1",
            "approvalStatus",
            "APPROVED",
            "actionType",
            "CATCH_UP",
            "payloadJson",
            approvalPayload.get())));
    when(orchestrator.loadApproval(eq(tenant), eq("approval-1"), any())).thenAnswer(invocation -> {
      Class<?> responseType = invocation.getArgument(2);
      return JsonUtils.fromJson(approvalJson, responseType);
    });
    var approvals = new DefaultConsoleApprovalApplicationService(
        orchestrator,
        mock(ConsoleRequestMetadataResolver.class),
        mock(ConsoleJobRecoveryService.class),
        jobApproval,
        mock(ConsoleFileApplicationService.class));

    assertThat(approvals.approve(tenant, "approval-1", "operator", "approved")).isEqualTo("run-1");
    assertThat(mapper.selectJobSummaries(tenant, calendar, day))
        .singleElement()
        .satisfies(row -> assertThat(row.catchupCount()).isEqualTo(1L));
  }

  @Test
  @DisplayName("批日作业汇总识别 Workflow 补跑快照并隔离其它日历和演练")
  void shouldCountWorkflowCatchUpsOnlyForTheRequestedCalendar() {
    final String insertWorkflowSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, single_job_definition_id, calendar_code)
        values (?, ?, 'test', 'DAG', ?, ?) returning id
        """;
    final String insertRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,
            biz_date, dedup_key, request_status, dry_run, launch_payload)
        values (?, ?, 'CATCH_UP', ?, ?, ?, ?, ?, ?::jsonb)
        """;
    String tenant = unique("tenant");
    String calendar = unique("calendar");
    String otherCalendar = unique("calendar");
    String job = unique("job");
    LocalDate day = LocalDate.of(2026, 9, 8);
    Long definition = insertJobDefinition(tenant, calendar, job);
    insertJobInstance(tenant, definition, job, day, "FAILED", false);
    Long workflow = jdbcTemplate.queryForObject(
        insertWorkflowSql, Long.class, tenant, unique("workflow"), definition, calendar);
    String schedulerKey = tenant + ":batch-day-catchup:" + calendar + ":" + day + ":" + job;
    String schedulerPayload =
        JsonUtils.toJson(Map.of("params", Map.of("batchDayCalendarCode", calendar)));
    String manualPayload = JsonUtils.toJson(
        Map.of("params", Map.of("batchDayCalendarCode", calendar, "jobCode", job)));
    String otherPayload = JsonUtils.toJson(
        Map.of("params", Map.of("batchDayCalendarCode", otherCalendar, "jobCode", job)));
    jdbcTemplate.update(
        insertRequestSql,
        tenant,
        unique("request"),
        workflow,
        day,
        schedulerKey,
        "ACCEPTED",
        false,
        schedulerPayload);
    jdbcTemplate.update(
        insertRequestSql,
        tenant,
        unique("request"),
        workflow,
        day,
        unique("manual"),
        "LAUNCHED",
        false,
        manualPayload);
    jdbcTemplate.update(
        insertRequestSql,
        tenant,
        unique("request"),
        workflow,
        day,
        unique("other"),
        "LAUNCHED",
        false,
        otherPayload);
    jdbcTemplate.update(
        insertRequestSql,
        tenant,
        unique("request"),
        workflow,
        day,
        unique("dry"),
        "LAUNCHED",
        true,
        manualPayload);
    jdbcTemplate.update(
        insertRequestSql,
        tenant,
        unique("request"),
        workflow,
        day,
        unique("rejected"),
        "REJECTED",
        false,
        manualPayload);

    assertThat(mapper.selectJobSummaries(tenant, calendar, day))
        .singleElement()
        .satisfies(row -> assertThat(row.catchupCount()).isEqualTo(2L));
  }

  @Test
  @DisplayName("批日统计和补跑候选使用实例冻结日历，不随作业定义日历变化")
  void shouldUseFrozenInstanceCalendarWhenDefinitionChanges() {
    final String freezeInstanceSql =
        "update batch.job_instance set calendar_code=? where tenant_id=? and job_code=?";
    final String changeDefinitionSql =
        "update batch.job_definition set calendar_code=? where tenant_id=? and id=?";
    final String insertDaySql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status)
        values (?, ?, ?, 'FAILED')
        """;
    String tenant = unique("tenant");
    String calendar = unique("calendar");
    String job = unique("job");
    LocalDate day = LocalDate.of(2026, 9, 8);
    Long definition = insertJobDefinition(tenant, calendar, job);
    insertJobInstance(tenant, definition, job, day, "FAILED", false);
    jdbcTemplate.update(freezeInstanceSql, calendar, tenant, job);
    jdbcTemplate.update(changeDefinitionSql, unique("other-calendar"), tenant, definition);
    jdbcTemplate.update(insertDaySql, tenant, calendar, day);

    assertThat(mapper.selectWindow(tenant, calendar, day).failedJobCount()).isEqualTo(1L);
    var list = mapper.selectByQuery(BatchDayQuery.builder()
        .tenantId(tenant)
        .calendarCode(calendar)
        .fromBizDate(day)
        .toBizDate(day)
        .pageRequest(new PageRequest(1, 10))
        .build());
    assertThat(list)
        .singleElement()
        .satisfies(row -> assertThat(row.failedJobCount()).isEqualTo(1L));
    assertThat(mapper.selectJobSummaries(tenant, calendar, day))
        .extracting(BatchDayJobSummaryView::jobCode)
        .containsExactly(job);
    assertThat(mapper.selectFailedJobCodes(tenant, calendar, day)).containsExactly(job);
  }

  @Test
  @DisplayName("默认补跑候选仅包含当前日历正式失败作业")
  void shouldExcludeDryRunFailuresFromCatchUpCandidates() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String failedCode = unique("failed");
    String partialCode = unique("partial");
    String dryCode = unique("dry");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Long failedDefinition = insertJobDefinition(tenantId, calendarCode, failedCode);
    Long partialDefinition = insertJobDefinition(tenantId, calendarCode, partialCode);
    Long dryDefinition = insertJobDefinition(tenantId, calendarCode, dryCode);
    insertJobInstance(tenantId, failedDefinition, failedCode, bizDate, "FAILED", false);
    insertJobInstance(tenantId, partialDefinition, partialCode, bizDate, "PARTIAL_FAILED", false);
    insertJobInstance(tenantId, dryDefinition, dryCode, bizDate, "FAILED", true);

    assertThat(mapper.selectFailedJobCodes(tenantId, calendarCode, bizDate))
        .containsExactlyInAnyOrder(failedCode, partialCode);
  }

  @Test
  @DisplayName("日窗口 occurrence 只返回当前租户、日历和业务日，并保留关闭日拒绝原因")
  void shouldListScheduledOccurrencesForRequestedTenantCalendarAndDate() {
    String tenantId = unique("tenant");
    String otherTenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    Instant fireTime = Instant.parse("2026-10-15T02:00:00Z");

    insertScheduledOccurrence(
        tenantId,
        calendarCode,
        "workflow-closed-fire",
        fireTime,
        bizDate,
        "CLOSED_DAY_REJECTED",
        "BATCH_DAY_CLOSED: explicit CATCH_UP or RERUN request required");
    insertScheduledOccurrence(
        tenantId,
        unique("calendar"),
        "workflow-other-calendar",
        fireTime.plusSeconds(60),
        bizDate,
        "REQUESTED",
        null);
    insertScheduledOccurrence(
        otherTenantId,
        calendarCode,
        "workflow-other-tenant",
        fireTime.plusSeconds(120),
        bizDate,
        "REQUESTED",
        null);
    insertScheduledOccurrence(
        tenantId,
        calendarCode,
        "workflow-other-date",
        fireTime.plusSeconds(180),
        bizDate.plusDays(1),
        "REQUESTED",
        null);

    List<BatchDayScheduledOccurrenceView> occurrences =
        mapper.selectScheduledOccurrences(tenantId, calendarCode, bizDate);

    assertThat(occurrences).hasSize(1);
    BatchDayScheduledOccurrenceView rejected = occurrences.getFirst();
    assertThat(rejected.workflowCode()).isEqualTo("workflow-closed-fire");
    assertThat(rejected.occurrenceStatus()).isEqualTo("CLOSED_DAY_REJECTED");
    assertThat(rejected.requestId()).isNull();
    assertThat(rejected.lastRejectionReason())
        .contains("explicit CATCH_UP or RERUN request required");
  }

  private void insertScheduledOccurrence(
      String tenantId,
      String calendarCode,
      String workflowCode,
      Instant fireTime,
      LocalDate bizDate,
      String status,
      String rejectionReason) {
    final String insertWorkflowDefinitionSql = """
    insert into batch.workflow_definition
    (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code)
    values (?, ?, ?, 'DAG', 1, true, ?) returning id
    """;
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        workflowCode,
        calendarCode);
    final String insertScheduledOccurrenceSql = """
    insert into batch.scheduled_occurrence (
    tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
    scheduled_fire_time, biz_date, occurrence_status, last_rejection_reason
    ) values (?, ?, ?, 1, ?, ?, ?, ?, ?)
    """;
    Timestamp scheduledFireTimestamp = Timestamp.from(fireTime);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowId,
        workflowCode,
        calendarCode,
        scheduledFireTimestamp,
        bizDate,
        status,
        rejectionReason);
  }

  private Long insertJobDefinition(String tenantId, String calendarCode, String jobCode) {
    String insertJobDefinitionSql = """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code
        ) values (?, ?, 'Batch Day Console Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, calendarCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    String suffix = UUID.randomUUID().toString();
    final String insertJobInstanceSql = """
    insert into batch.job_instance(
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, dry_run, calendar_code
    ) values (?, ?, ?, ?, ?, 'MANUAL', ?, 5, ?, 0, 0, 0, ?, ?,
              (select calendar_code from batch.job_definition where tenant_id = ? and id = ?))
    """;
    String instanceNumber = "INST-" + suffix;
    String dedupKey = "DEDUP-" + suffix;
    String traceId = "TRACE-" + suffix;
    jdbcTemplate.update(
        insertJobInstanceSql,
        tenantId,
        definitionId,
        jobCode,
        instanceNumber,
        bizDate,
        status,
        dedupKey,
        traceId,
        dryRun,
        tenantId,
        definitionId);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
