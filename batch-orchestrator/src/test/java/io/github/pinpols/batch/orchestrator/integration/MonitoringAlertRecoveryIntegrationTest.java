package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.AlertStatus;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.AlertFingerprints;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.governance.AlertmanagerReemitService;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("软时限自动解除的真实来源、发生回执及事务边界")
class MonitoringAlertRecoveryIntegrationTest extends AbstractIntegrationTest {
  private final JdbcTemplate jdbc;
  private final AlertEventMapper mapper;
  private final AlertmanagerReemitService reemitService;
  private final TransactionTemplate transactions;
  private final List<String> tenants = new ArrayList<>();

  @Autowired
  MonitoringAlertRecoveryIntegrationTest(
      JdbcTemplate jdbc,
      AlertEventMapper mapper,
      AlertmanagerReemitService reemitService,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.reemitService = reemitService;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    String deleteInstancesSql = "DELETE FROM batch.job_instance WHERE tenant_id = ?";
    String deleteArchivedInstancesSql =
        "DELETE FROM archive.job_instance_archive WHERE tenant_id = ?";
    String deleteChildRequestsSql =
        "DELETE FROM batch.trigger_request WHERE tenant_id = ? AND parent_workflow_run_id IS NOT NULL";
    for (String tenant : tenants) {
      jdbc.update(deleteInstancesSql, tenant);
      jdbc.update(deleteArchivedInstancesSql, tenant);
      jdbc.update(deleteChildRequestsSql, tenant);
      for (String table : List.of(
          "batch.alert_event",
          "batch.scheduled_occurrence",
          "batch.expected_work_occurrence",
          "batch.workflow_run",
          "archive.workflow_run_archive",
          "batch.trigger_request",
          "batch.job_monitoring_policy",
          "batch.workflow_monitoring_policy",
          "batch.workflow_definition",
          "batch.job_definition")) {
        String deleteSql = "DELETE FROM %s WHERE tenant_id = ?".formatted(table);
        jdbc.update(deleteSql, tenant);
      }
    }
  }

  @Test
  @DisplayName("Job 三类违约分别等待真实开始或终止和首次发生回执，不改写作业结果")
  void shouldResolveJobPhasesOnlyAfterSourceProgressAndFiringDelivery() {
    JobFixture job = job("RUNNING", false);
    AlertEventEntity start = jobAlert(job, "JOB_NOT_STARTED_BY_DEADLINE");
    AlertEventEntity end = jobAlert(job, "JOB_NOT_COMPLETED_BY_DEADLINE");
    AlertEventEntity runtime = jobAlert(job, "JOB_RUNNING_TOO_LONG");
    assertThat(resolve(start)).isNull();
    String startSql = """
        UPDATE batch.job_instance SET execution_started_at = current_timestamp - interval '1 minute'
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(startSql, job.tenant(), job.id());
    assertThat(resolve(start)).isNull();
    firing(start);
    reemitService.reemitTenant(job.tenant());
    assertClosed(start, "RECOVERED");
    assertThat(resolve(end)).isNull();
    assertThat(resolve(runtime)).isNull();

    String finishSql = """
        UPDATE batch.job_instance SET instance_status = 'FAILED', finished_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(finishSql, job.tenant(), job.id());
    assertThat(resolve(end)).isNull();
    assertThat(resolve(runtime)).isNull();
    firing(end);
    firing(runtime);
    assertThat(resolve(end)).isNotNull();
    assertThat(resolve(runtime)).isNotNull();
    assertClosed(end, "RECOVERED");
    String statusSql =
        "SELECT instance_status FROM batch.job_instance WHERE tenant_id = ? AND id = ?";
    String sourceStatus = jdbc.queryForObject(statusSql, String.class, job.tenant(), job.id());
    assertThat(sourceStatus).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("旧 Job SLA 截止、耗时及升级告警也须在终态后逐条解除")
  void shouldResolveLegacyJobSlaAlertsAfterTerminalDelivery() {
    JobFixture deadlineJob = job("RUNNING", false);
    String disableSoftRuntimeSql =
        "UPDATE batch.job_monitoring_policy SET soft_runtime_seconds = 0 WHERE tenant_id = ? AND job_definition_id = ?";
    jdbc.update(disableSoftRuntimeSql, deadlineJob.tenant(), deadlineJob.definition());
    String updateDeadlineSql = """
        UPDATE batch.job_instance
        SET deadline_at = current_timestamp - interval '2 minutes',
            execution_started_at = current_timestamp - interval '3 minutes'
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(updateDeadlineSql, deadlineJob.tenant(), deadlineJob.id());
    Map<String, String> deadlineDetail = Map.of(
        "jobInstanceId", Long.toString(deadlineJob.id()), "violationReason", "DEADLINE_EXCEEDED");
    AlertEventEntity deadline = alert(
        deadlineJob.tenant(), "JOB_SLA_VIOLATION", Long.toString(deadlineJob.id()), deadlineDetail);
    AlertEventEntity deadlineEscalation = alert(
        deadlineJob.tenant(),
        "JOB_SLA_VIOLATION_ESCALATED",
        Long.toString(deadlineJob.id()),
        deadlineDetail);
    AlertEventEntity unknownLegacyViolation = alert(
        deadlineJob.tenant(),
        "JOB_SLA_VIOLATION",
        "unknown-" + deadlineJob.id(),
        Map.of("jobInstanceId", Long.toString(deadlineJob.id()), "violationReason", "UNKNOWN"));

    JobFixture durationJob = job("RUNNING", false);
    jdbc.update(disableSoftRuntimeSql, durationJob.tenant(), durationJob.definition());
    String updateDurationSql = """
        UPDATE batch.job_instance
        SET expected_duration_seconds = 30,
            execution_started_at = current_timestamp - interval '2 minutes'
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(updateDurationSql, durationJob.tenant(), durationJob.id());
    AlertEventEntity duration = alert(
        durationJob.tenant(),
        "JOB_SLA_VIOLATION",
        Long.toString(durationJob.id()),
        Map.of(
            "jobInstanceId",
            Long.toString(durationJob.id()),
            "violationReason",
            "EXPECTED_DURATION_EXCEEDED"));

    assertThat(resolve(deadline)).isNull();
    assertThat(resolve(deadlineEscalation)).isNull();
    assertThat(resolve(duration)).isNull();

    String finishSql = """
        UPDATE batch.job_instance
        SET instance_status = 'FAILED', finished_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(finishSql, deadlineJob.tenant(), deadlineJob.id());
    jdbc.update(finishSql, durationJob.tenant(), durationJob.id());
    assertThat(resolve(deadline)).isNull();
    assertThat(resolve(deadlineEscalation)).isNull();
    assertThat(resolve(duration)).isNull();

    firing(deadline);
    firing(deadlineEscalation);
    firing(unknownLegacyViolation);
    firing(duration);
    reemitService.reemitTenant(deadlineJob.tenant());
    reemitService.reemitTenant(durationJob.tenant());
    assertThat(read(deadline).getStatus()).isEqualTo("CLOSED");
    assertThat(read(deadlineEscalation).getStatus()).isEqualTo("CLOSED");
    assertThat(read(unknownLegacyViolation).getStatus()).isEqualTo("OPEN");
    assertThat(read(duration).getStatus()).isEqualTo("CLOSED");
    assertClosed(deadline, "RECOVERED");
    assertClosed(deadlineEscalation, "RECOVERED");
    assertClosed(duration, "RECOVERED");
  }

  @Test
  @DisplayName("策略逐项停用或删除按撤销关闭，不伪造自然恢复也不撤销仍启用的规则")
  void shouldRecordPolicyWithdrawalSeparatelyFromRecovery() {
    JobFixture job = job("RUNNING", false);
    AlertEventEntity runtime = jobAlert(job, "JOB_RUNNING_TOO_LONG");
    AlertEventEntity start = jobAlert(job, "JOB_NOT_STARTED_BY_DEADLINE");
    String disableRuntimeSql = """
        UPDATE batch.job_monitoring_policy SET soft_runtime_seconds = 0 WHERE tenant_id = ?
        """;
    jdbc.update(disableRuntimeSql, job.tenant());
    assertThat(resolve(runtime)).isNotNull();
    assertClosed(runtime, "POLICY_DISABLED");
    AlertEventEntity stored = read(runtime);
    assertThat(JsonUtils.fromJson(stored.getDetailJson(), Map.class).get("monitoringRecoveredAt"))
        .isNull();
    assertThat(resolve(start)).isNull();
    String deletePolicySql = "DELETE FROM batch.job_monitoring_policy WHERE tenant_id = ?";
    jdbc.update(deletePolicySql, job.tenant());
    assertThat(resolve(start)).isNotNull();
    assertClosed(start, "POLICY_DISABLED");

    WorkflowFixture workflow = workflow();
    long run = run(workflow, "RUNNING", false);
    AlertEventEntity root =
        workflowAlert(workflow, "WORKFLOW_RUNNING_TOO_LONG", "workflow_run:" + run);
    String disableWorkflowSql = """
        UPDATE batch.workflow_monitoring_policy SET soft_runtime_seconds = 0 WHERE tenant_id = ?
        """;
    jdbc.update(disableWorkflowSql, workflow.tenant());
    assertThat(resolve(root)).isNotNull();
    assertClosed(root, "POLICY_DISABLED");
  }

  @Test
  @DisplayName("仅有派发或机会请求不算启动，根开始与完成分别解除且历史补报保留发生阶段")
  void shouldResolveWorkflowOpportunityAndRuntimeIndependently() {
    WorkflowFixture workflow = workflow();
    long occurrence = scheduled(workflow, "REQUESTED");
    String resource = "scheduled_occurrence:" + occurrence;
    AlertEventEntity start = workflowAlert(workflow, "WORKFLOW_NOT_STARTED_BY_DEADLINE", resource);
    AlertEventEntity end = workflowAlert(workflow, "WORKFLOW_NOT_COMPLETED_BY_DEADLINE", resource);
    firing(start);
    firing(end);
    assertThat(resolve(start)).isNull();
    assertThat(resolve(end)).isNull();
    long run = run(workflow, "RUNNING", false);
    assertThat(resolve(start)).isNotNull();
    assertThat(resolve(end)).isNull();
    AlertEventEntity runtime =
        workflowAlert(workflow, "WORKFLOW_RUNNING_TOO_LONG", "workflow_run:" + run);
    firing(runtime);
    assertThat(resolve(runtime)).isNull();
    String finishSql = """
        UPDATE batch.workflow_run SET run_status = 'FAILED', finished_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(finishSql, workflow.tenant(), run);
    assertThat(resolve(end)).isNotNull();
    assertThat(resolve(runtime)).isNotNull();
    assertClosed(end, "RECOVERED");
    assertClosed(runtime, "RECOVERED");
  }

  @Test
  @DisplayName("Workflow 子作业请求在实例创建前保持告警，真实开始或明确终结后解除")
  void shouldResolveWorkflowChildJobAlertsFromRequestProgress() {
    WorkflowChildJobFixture child = workflowChildJob();
    String resource = "workflow_job_request:" + child.triggerRequestId();
    AlertEventEntity start = workflowChildJobAlert(child, "JOB_NOT_STARTED_BY_DEADLINE", resource);
    AlertEventEntity completion =
        workflowChildJobAlert(child, "JOB_NOT_COMPLETED_BY_DEADLINE", resource);
    firing(start);
    firing(completion);

    assertThat(resolve(start)).isNull();
    assertThat(resolve(completion)).isNull();

    long instanceId = createWorkflowChildJobInstance(child, "RUNNING", true);
    assertThat(resolve(start)).isNotNull();
    assertClosed(start, "RECOVERED");
    assertThat(resolve(completion)).isNull();

    String finishSql = """
        UPDATE batch.job_instance
        SET instance_status = 'FAILED', finished_at = current_timestamp, updated_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(finishSql, child.workflow().tenant(), instanceId);
    assertThat(resolve(completion)).isNotNull();
    assertClosed(completion, "RECOVERED");

    WorkflowChildJobFixture rejected = workflowChildJob();
    String rejectedResource = "workflow_job_request:" + rejected.triggerRequestId();
    AlertEventEntity rejectedStart =
        workflowChildJobAlert(rejected, "JOB_NOT_STARTED_BY_DEADLINE", rejectedResource);
    firing(rejectedStart);
    String rejectRequestSql = """
        UPDATE batch.trigger_request
        SET request_status = 'REJECTED', updated_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(rejectRequestSql, rejected.workflow().tenant(), rejected.triggerRequestId());
    assertThat(resolve(rejectedStart)).isNotNull();
    assertClosed(rejectedStart, "OPPORTUNITY_CLOSED");
  }

  @Test
  @DisplayName("无 Run 的机会只在明确终结后解除，输入已到达或已满足不代替运行完成")
  void shouldResolveAbandonedOpportunitiesWithoutInventingBusinessSuccess() {
    WorkflowFixture workflow = workflow();
    long occurrence = scheduled(workflow, "WAITING_READINESS");
    AlertEventEntity alert = workflowAlert(
        workflow, "WORKFLOW_NOT_COMPLETED_BY_DEADLINE", "scheduled_occurrence:" + occurrence);
    firing(alert);
    assertThat(resolve(alert)).isNull();
    String closeOccurrenceSql = """
        UPDATE batch.scheduled_occurrence SET occurrence_status = 'READINESS_TIMEOUT', updated_at = current_timestamp
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(closeOccurrenceSql, workflow.tenant(), occurrence);
    assertThat(resolve(alert)).isNotNull();
    assertClosed(alert, "OPPORTUNITY_CLOSED");

    for (String state : List.of("EXPECTED", "ARRIVED", "SATISFIED", "EXPIRED", "CANCELLED")) {
      long expected = expected(workflow, state);
      AlertEventEntity input = workflowAlert(
          workflow, "WORKFLOW_NOT_STARTED_BY_DEADLINE", "expected_work_occurrence:" + expected);
      firing(input);
      AlertEventEntity closed = resolve(input);
      assertThat(closed != null)
          .as("输入机会状态 %s", state)
          .isEqualTo(Set.of("EXPIRED", "CANCELLED").contains(state));
      if (closed != null) {
        assertClosed(input, "OPPORTUNITY_CLOSED");
      }
    }
  }

  @Test
  @DisplayName("冷热源均可核对，首次无 Run 的告警在根归档后仍通过原请求恢复")
  void shouldResolveArchivedSourcesAndPreserveUnobservedSources() {
    JobFixture job = job("FAILED", true);
    AlertEventEntity archivedJob = jobAlert(job, "JOB_RUNNING_TOO_LONG");
    firing(archivedJob);
    assertThat(resolve(archivedJob)).isNotNull();
    WorkflowFixture workflow = workflow();
    long occurrence = scheduled(workflow, "REQUESTED");
    AlertEventEntity opportunity = workflowAlert(
        workflow, "WORKFLOW_NOT_COMPLETED_BY_DEADLINE", "scheduled_occurrence:" + occurrence);
    firing(opportunity);
    assertThat(resolve(opportunity)).isNull();
    long archivedRun = run(workflow, "FAILED", true);
    assertThat(resolve(opportunity)).isNotNull();
    AlertEventEntity archivedRuntime =
        workflowAlert(workflow, "WORKFLOW_RUNNING_TOO_LONG", "workflow_run:" + archivedRun);
    firing(archivedRuntime);
    assertThat(resolve(archivedRuntime)).isNotNull();
    AlertEventEntity unknown = alert(
        job.tenant(),
        "JOB_RUNNING_TOO_LONG",
        "missing",
        Map.of("jobInstanceId", Long.toString(Long.MAX_VALUE)));
    firing(unknown);
    assertThat(resolve(unknown)).isNull();
    AlertEventEntity malformed = alert(
        job.tenant(),
        "JOB_RUNNING_TOO_LONG",
        "malformed",
        Map.of("jobInstanceId", "999999999999999999999999999999999999999999"));
    firing(malformed);
    assertThat(resolve(malformed)).isNull();
  }

  @Test
  @DisplayName("终态判定与 Job 和 Workflow 生命周期枚举一致，活跃状态即使误带结束时间也不解除")
  void shouldKeepTerminalClassificationAlignedWithDomainLifecycle() {
    Set<String> closedJobs = new HashSet<>();
    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      JobFixture fixture = job(status.code(), false);
      AlertEventEntity alert = jobAlert(fixture, "JOB_RUNNING_TOO_LONG");
      firing(alert);
      if (resolve(alert) != null) {
        closedJobs.add(status.code());
      }
    }
    assertThat(closedJobs).containsExactlyInAnyOrderElementsOf(JobInstanceStatus.terminalCodes());
    Set<String> closedWorkflows = new HashSet<>();
    for (WorkflowRunStatus status : WorkflowRunStatus.values()) {
      WorkflowFixture fixture = workflow();
      long run = run(fixture, status.code(), false);
      AlertEventEntity alert =
          workflowAlert(fixture, "WORKFLOW_RUNNING_TOO_LONG", "workflow_run:" + run);
      firing(alert);
      if (resolve(alert) != null) {
        closedWorkflows.add(status.code());
      }
    }
    assertThat(closedWorkflows)
        .containsExactlyInAnyOrderElementsOf(WorkflowRunStatus.terminalCodes());
  }

  @Test
  @DisplayName("外租户、旧快照、人工关闭和仅恢复回执均不能绕过发生回执保护")
  void shouldRejectForeignStaleAndManuallyClosedSnapshots() {
    JobFixture job = job("FAILED", false);
    AlertEventEntity alert = jobAlert(job, "JOB_RUNNING_TOO_LONG");
    String resolvedOnlySql = """
        INSERT INTO batch.alert_notification_receipt (tenant_id, dedup_fingerprint, receiver_code, resolved_delivered_at)
        VALUES (?, ?, 'resolved-only', current_timestamp)
        """;
    jdbc.update(resolvedOnlySql, alert.getTenantId(), alert.getDedupFingerprint());
    assertThat(resolve(alert)).isNull();
    firing(alert);
    String tenant = alert.getTenantId();
    alert.setTenantId("foreign-tenant");
    assertThat(resolve(alert)).isNull();
    alert.setTenantId(tenant);
    String changeSql = """
        UPDATE batch.alert_event SET updated_at = current_timestamp, title = '并发信号更新'
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(changeSql, tenant, alert.getId());
    assertThat(resolve(alert)).isNull();
    AlertEventEntity current = read(alert);
    String manualSql = """
        UPDATE batch.alert_event SET status = 'CLOSED', closed_at = current_timestamp, close_reason = 'MANUAL'
        WHERE tenant_id = ? AND id = ?
        """;
    jdbc.update(manualSql, tenant, alert.getId());
    assertThat(resolve(current)).isNull();
    assertClosed(alert, "MANUAL");
  }

  @Test
  @DisplayName("自动关闭随事务回滚且确认、静默告警均可在后续轮询自然恢复")
  void shouldRollbackClosureAndRecoverAcknowledgedOrSuppressedAlerts() {
    JobFixture job = job("FAILED", false);
    AlertEventEntity alert = jobAlert(job, "JOB_RUNNING_TOO_LONG");
    firing(alert);
    transactions.executeWithoutResult(status -> {
      mapper.bindReemitTenant(job.tenant());
      assertThat(mapper.closeEligibleMonitoringAlert(alert, AlertStatus.CLOSED.code()))
          .isNotNull();
      status.setRollbackOnly();
    });
    assertThat(read(alert).getStatus()).isEqualTo("OPEN");
    for (String state : List.of("ACKED", "SUPPRESSED")) {
      AlertEventEntity phase = jobAlert(
          job,
          state.equals("ACKED") ? "JOB_NOT_STARTED_BY_DEADLINE" : "JOB_NOT_COMPLETED_BY_DEADLINE");
      String stateSql = "UPDATE batch.alert_event SET status = ? WHERE tenant_id = ? AND id = ?";
      jdbc.update(stateSql, state, job.tenant(), phase.getId());
      phase.setStatus(state);
      firing(phase);
      assertThat(resolve(phase)).isNotNull();
      assertClosed(phase, "RECOVERED");
    }
  }

  private AlertEventEntity resolve(AlertEventEntity alert) {
    return transactions.execute(status -> {
      mapper.bindReemitTenant(alert.getTenantId());
      return mapper.closeEligibleMonitoringAlert(alert, AlertStatus.CLOSED.code());
    });
  }

  private AlertEventEntity read(AlertEventEntity alert) {
    return mapper.selectByFingerprint(alert.getTenantId(), alert.getDedupFingerprint());
  }

  private void assertClosed(AlertEventEntity original, String reason) {
    AlertEventEntity stored = read(original);
    assertThat(stored.getStatus()).isEqualTo("CLOSED");
    assertThat(stored.getCloseReason()).isEqualTo(reason);
    assertThat(stored.getClosedAt()).isNotNull();
    assertThat(resolve(original)).isNull();
  }

  private void firing(AlertEventEntity alert) {
    String receiptSql = """
        INSERT INTO batch.alert_notification_receipt (tenant_id, dedup_fingerprint, receiver_code, firing_delivered_at)
        VALUES (?, ?, 'batch-sla', current_timestamp)
        """;
    jdbc.update(receiptSql, alert.getTenantId(), alert.getDedupFingerprint());
  }

  private AlertEventEntity jobAlert(JobFixture fixture, String type) {
    String resource = Long.toString(fixture.id());
    return alert(fixture.tenant(), type, resource, Map.of("jobInstanceId", resource));
  }

  private AlertEventEntity workflowAlert(WorkflowFixture fixture, String type, String resource) {
    return alert(
        fixture.tenant(),
        type,
        resource,
        Map.of(
            "workflowDefinitionId",
            Long.toString(fixture.definition()),
            "monitoringResourceKey",
            resource));
  }

  private AlertEventEntity workflowChildJobAlert(
      WorkflowChildJobFixture fixture, String type, String resource) {
    return alert(
        fixture.workflow().tenant(),
        type,
        resource,
        Map.of(
            "triggerRequestId", Long.toString(fixture.triggerRequestId()),
            "workflowRunId", Long.toString(fixture.workflowRunId()),
            "monitoringResourceKey", resource));
  }

  private AlertEventEntity alert(
      String tenant, String type, String resource, Map<String, String> detail) {
    String fingerprint = AlertFingerprints.build(tenant, type, resource);
    String detailJson = JsonUtils.toJson(detail);
    String insertSql = """
        INSERT INTO batch.alert_event (tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint)
        VALUES (?, 'batch-orchestrator', ?, 'WARN', '自动解除验证', ?::jsonb, ?)
        """;
    jdbc.update(insertSql, tenant, type, detailJson, fingerprint);
    return mapper.selectByFingerprint(tenant, fingerprint);
  }

  private String tenant() {
    String tenant = "sla-recovery-" + UUID.randomUUID();
    tenants.add(tenant);
    return tenant;
  }

  private JobFixture job(String state, boolean archived) {
    String tenant = tenant();
    String definitionSql = """
        INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode)
        VALUES (?, 'RECOVERY_JOB', '自动解除验证', 'GENERAL', 'MANUAL', 'UTC', 'API') RETURNING id
        """;
    long definition = jdbc.queryForObject(definitionSql, Long.class, tenant);
    String policySql = """
        INSERT INTO batch.job_monitoring_policy (tenant_id, job_definition_id, soft_runtime_seconds, start_grace_seconds,
            dependency_completion_window_seconds) VALUES (?, ?, 30, 30, 60)
        """;
    jdbc.update(policySql, tenant, definition);
    String table = archived ? "archive.job_instance_archive" : "batch.job_instance";
    String instanceSql = """
        INSERT INTO %s (tenant_id, job_definition_id, job_code, instance_no, trigger_type, instance_status, dedup_key, biz_date,
            started_at, finished_at, dry_run)
        VALUES (?, ?, 'RECOVERY_JOB', ?, 'MANUAL', ?, ?, current_date, current_timestamp - interval '2 minutes',
            current_timestamp - interval '1 minute', false) RETURNING id
        """.formatted(table);
    long id =
        jdbc.queryForObject(instanceSql, Long.class, tenant, definition, tenant, state, tenant);
    if (state.equals("RUNNING")) {
      String runningSql =
          "UPDATE %s SET finished_at = NULL WHERE tenant_id = ? AND id = ?".formatted(table);
      jdbc.update(runningSql, tenant, id);
    }
    return new JobFixture(tenant, definition, id);
  }

  private WorkflowFixture workflow() {
    String tenant = tenant();
    String insertCalendarSql =
        "INSERT INTO batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone) VALUES (?, 'RECOVERY_CALENDAR', '自动解除验证', 'UTC')";
    jdbc.update(insertCalendarSql, tenant);
    String definitionSql = """
        INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)
        VALUES (?, 'RECOVERY_WORKFLOW', '自动解除验证', 'RECOVERY_CALENDAR') RETURNING id
        """;
    long definition = jdbc.queryForObject(definitionSql, Long.class, tenant);
    String policySql = """
        INSERT INTO batch.workflow_monitoring_policy (tenant_id, workflow_definition_id, soft_runtime_seconds,
            start_grace_seconds, completion_deadline_local_time) VALUES (?, ?, 30, 30, TIME '00:00')
        """;
    jdbc.update(policySql, tenant, definition);
    String requestKey = "request-" + tenant;
    String requestSql = """
        INSERT INTO batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id, dedup_key, request_status, biz_date)
        VALUES (?, ?, 'MANUAL', ?, ?, 'LAUNCHED', current_date) RETURNING id
        """;
    long request =
        jdbc.queryForObject(requestSql, Long.class, tenant, requestKey, definition, requestKey);
    return new WorkflowFixture(tenant, definition, request, requestKey);
  }

  private WorkflowChildJobFixture workflowChildJob() {
    WorkflowFixture workflow = workflow();
    long workflowRunId = run(workflow, "RUNNING", false);
    String jobCode = "RECOVERY_CHILD_JOB";
    String definitionSql = """
        INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode)
        VALUES (?, ?, '子作业自动解除验证', 'GENERAL', 'MANUAL', 'UTC', 'API') RETURNING id
        """;
    long jobDefinitionId =
        jdbc.queryForObject(definitionSql, Long.class, workflow.tenant(), jobCode);
    String policySql = """
        INSERT INTO batch.job_monitoring_policy (tenant_id, job_definition_id, soft_runtime_seconds,
            start_grace_seconds, dependency_completion_window_seconds)
        VALUES (?, ?, 30, 30, 60)
        """;
    jdbc.update(policySql, workflow.tenant(), jobDefinitionId);
    String requestKey = "child-request-" + workflow.tenant();
    String requestSql = """
        INSERT INTO batch.trigger_request (
            tenant_id, request_id, job_code, biz_date, trigger_type, dedup_key, request_status,
            dry_run, parent_workflow_run_id, parent_node_code
        ) VALUES (?, ?, ?, current_date, 'EVENT', ?, 'ACCEPTED', false, ?, 'CHILD_NODE')
        RETURNING id
        """;
    long triggerRequestId = jdbc.queryForObject(
        requestSql, Long.class, workflow.tenant(), requestKey, jobCode, requestKey, workflowRunId);
    return new WorkflowChildJobFixture(
        workflow, workflowRunId, jobDefinitionId, jobCode, triggerRequestId);
  }

  private long createWorkflowChildJobInstance(
      WorkflowChildJobFixture fixture, String status, boolean executionStarted) {
    String startedAt = executionStarted ? "current_timestamp - interval '1 minute'" : "NULL";
    String instanceSql = """
        INSERT INTO batch.job_instance (
            tenant_id, job_definition_id, job_code, instance_no, trigger_type, instance_status,
            dedup_key, biz_date, execution_started_at, dry_run, trigger_request_id
        ) VALUES (?, ?, ?, ?, 'EVENT', ?, ?, current_date, %s, false, ?)
        RETURNING id
        """.formatted(startedAt);
    return jdbc.queryForObject(
        instanceSql,
        Long.class,
        fixture.workflow().tenant(),
        fixture.jobDefinitionId(),
        fixture.jobCode(),
        "instance-" + fixture.triggerRequestId(),
        status,
        "dedup-" + fixture.triggerRequestId(),
        fixture.triggerRequestId());
  }

  private long run(WorkflowFixture fixture, String state, boolean archived) {
    String table = archived ? "archive.workflow_run_archive" : "batch.workflow_run";
    String runSql = """
        INSERT INTO %s (tenant_id, workflow_definition_id, trigger_request_id, run_status, biz_date, started_at, finished_at, dry_run)
        VALUES (?, ?, ?, ?, current_date, current_timestamp - interval '2 minutes', current_timestamp - interval '1 minute', false)
        RETURNING id
        """.formatted(table);
    long id = jdbc.queryForObject(
        runSql, Long.class, fixture.tenant(), fixture.definition(), fixture.request(), state);
    if (state.equals("RUNNING")) {
      String runningSql =
          "UPDATE %s SET finished_at = NULL WHERE tenant_id = ? AND id = ?".formatted(table);
      jdbc.update(runningSql, fixture.tenant(), id);
    }
    return id;
  }

  private long scheduled(WorkflowFixture fixture, String state) {
    String occurrenceSql = """
        INSERT INTO batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code, schedule_version,
            scheduled_fire_time, occurrence_status, request_id)
        VALUES (?, ?, 'RECOVERY_WORKFLOW', 1, current_timestamp - interval '5 minutes', ?, ?) RETURNING id
        """;
    return jdbc.queryForObject(
        occurrenceSql,
        Long.class,
        fixture.tenant(),
        fixture.definition(),
        state,
        fixture.requestKey());
  }

  private long expected(WorkflowFixture fixture, String state) {
    String occurrenceSql = """
        INSERT INTO batch.expected_work_occurrence (tenant_id, workflow_definition_id, calendar_code, biz_date,
            source_type, source_key, occurrence_status)
        VALUES (?, ?, 'recovery', current_date, 'EXTERNAL_EVENT', ?, ?) RETURNING id
        """;
    return jdbc.queryForObject(
        occurrenceSql, Long.class, fixture.tenant(), fixture.definition(), state, state);
  }

  private record JobFixture(String tenant, long definition, long id) {}

  private record WorkflowFixture(String tenant, long definition, long request, String requestKey) {}

  private record WorkflowChildJobFixture(
      WorkflowFixture workflow,
      long workflowRunId,
      long jobDefinitionId,
      String jobCode,
      long triggerRequestId) {}
}
