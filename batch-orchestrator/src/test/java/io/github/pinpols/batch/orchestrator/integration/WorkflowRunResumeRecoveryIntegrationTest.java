package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.domain.param.WorkflowRunMarkRunningParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Workflow Run 恢复闭环")
class WorkflowRunResumeRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String FAILURE_WAIT = "FAILURE_WAIT";
  private static final String ALWAYS_WAIT = "ALWAYS_WAIT";
  private static final String SUCCESS_WAIT = "SUCCESS_WAIT";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private WorkflowRunManagementApplicationService workflowRunManagement;

  @Autowired
  private TaskOutcomeService taskOutcomeService;

  @Autowired
  private SecretPayloadProtector secretPayloadProtector;

  @Autowired
  private ExternalEventExpectationService externalEventExpectationService;

  @MockitoSpyBean
  private WorkflowNodeDispatchService nodeDispatchService;

  @MockitoSpyBean
  private WorkflowRunMapper workflowRunMapper;

  private String tenantId;
  private WorkflowLaunchSeed workflow;
  private Long workflowRunId;

  @Test
  @DisplayName("暂停期间失败回报后恢复两类后继，故障回滚与重复恢复均不重复派发")
  void shouldRecoverFailureAndAlwaysBranchesExactlyOnce_afterPausedFailureReport() {
    seedPausedFailureWorkflow();
    reportFailureWhilePaused();

    assertThat(runStatus()).isEqualTo("PAUSED");
    assertThat(nodeStatus("JOB")).isEqualTo("FAILED");
    assertThat(nodeRunCount(FAILURE_WAIT)).isZero();
    assertThat(nodeRunCount(ALWAYS_WAIT)).isZero();
    assertThat(nodeRunCount(SUCCESS_WAIT)).isZero();

    failResumeAfterFirstSuccessorDispatch();

    assertThat(runStatus()).isEqualTo("RUNNING");
    assertThat(nodeRunCount(FAILURE_WAIT)).isZero();
    assertThat(nodeRunCount(ALWAYS_WAIT)).isZero();
    assertThat(nodeRunCount(SUCCESS_WAIT)).isZero();

    Mockito.reset(nodeDispatchService);
    pauseAndResume();
    assertThat(nodeStatus(FAILURE_WAIT)).isEqualTo("RUNNING");
    assertThat(nodeStatus(ALWAYS_WAIT)).isEqualTo("RUNNING");
    assertThat(nodeStatus(SUCCESS_WAIT)).isEqualTo("SKIPPED");
    assertThat(nodeRunCount(FAILURE_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(ALWAYS_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(SUCCESS_WAIT)).isEqualTo(1);

    pauseAndResume();
    assertThat(nodeRunCount(FAILURE_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(ALWAYS_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(SUCCESS_WAIT)).isEqualTo(1);
  }

  @Test
  @DisplayName("暂停期间节点失败后恢复仍按接纳时冻结的 DAG 推进")
  void shouldResumePausedFailureUsingFrozenGraphAfterDefinitionChanges() {
    final String disableJobEdgesSql = """
        update batch.workflow_edge set enabled = false
        where tenant_id = ? and workflow_definition_id = ? and from_node_code = 'JOB'
        """;
    final String disableWaitNodesSql = """
        update batch.workflow_node set enabled = false
        where tenant_id = ? and workflow_definition_id = ? and node_type = 'WAIT'
        """;
    final String countEnabledJobEdgesSql = """
        select count(*) from batch.workflow_edge
        where tenant_id = ? and workflow_definition_id = ?
          and from_node_code = 'JOB' and enabled
        """;
    final String countEnabledWaitNodesSql = """
        select count(*) from batch.workflow_node
        where tenant_id = ? and workflow_definition_id = ?
          and node_type = 'WAIT' and enabled
        """;

    seedPausedFailureWorkflow();
    reportFailureWhilePaused();

    Long workflowDefinitionId = workflow.workflowDefinitionId();
    int disabledEdges = jdbc.update(
        disableJobEdgesSql,
        tenantId,
        workflowDefinitionId);
    int disabledWaitNodes = jdbc.update(
        disableWaitNodesSql,
        tenantId,
        workflowDefinitionId);
    assertThat(disabledEdges).isEqualTo(4);
    assertThat(disabledWaitNodes).isEqualTo(3);
    Integer remainingEnabledJobEdges = jdbc.queryForObject(
        countEnabledJobEdgesSql,
        Integer.class,
        tenantId,
        workflowDefinitionId);
    Integer remainingEnabledWaitNodes = jdbc.queryForObject(
        countEnabledWaitNodesSql,
        Integer.class,
        tenantId,
        workflowDefinitionId);
    assertThat(remainingEnabledJobEdges).isZero();
    assertThat(remainingEnabledWaitNodes).isZero();

    Runnable resumeWorkflow = () -> workflowRunManagement.resume(tenantId, workflowRunId);
    RlsTenantContextHolder.runWithTenant(tenantId, resumeWorkflow);

    assertThat(runStatus()).isEqualTo("RUNNING");
    assertThat(nodeStatus("JOB")).isEqualTo("FAILED");
    assertThat(nodeStatus(FAILURE_WAIT)).isEqualTo("RUNNING");
    assertThat(nodeStatus(ALWAYS_WAIT)).isEqualTo("RUNNING");
    assertThat(nodeStatus(SUCCESS_WAIT)).isEqualTo("SKIPPED");
    assertThat(nodeRunCount(FAILURE_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(ALWAYS_WAIT)).isEqualTo(1);
    assertThat(nodeRunCount(SUCCESS_WAIT)).isEqualTo(1);
  }

  @Test
  @DisplayName("首个节点派发前暂停时恢复仍使用受保护的根参数选择相同条件分支")
  void shouldResumeInitialDispatchWithOriginalProtectedPayload() {
    final String selectSourcePayloadProtectedSql = """
        select source_payload_protected::text from batch.workflow_node_run
        where workflow_run_id=? and node_code='START'
        """;
    final String selectLaunchPayloadSql = """
        select launch_payload #>> '{params,route}' from batch.trigger_request
        where tenant_id=? and parent_workflow_run_id=? and parent_node_code='JOB'
        """;
    final String selectLaunchPayloadSql2 = """
        select launch_payload #>> '{params,businessValue}' from batch.trigger_request
        where tenant_id=? and parent_workflow_run_id=? and parent_node_code='JOB'
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request
        where tenant_id=? and parent_workflow_run_id=? and parent_node_code='JOB_B'
        """;

    tenantId = "start-resume-" + UUID.randomUUID().toString().replace("-", "");
    String workerGroup = "WG-" + tenantId;
    workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, "GENERAL", workerGroup, TriggerType.API, BIZ_DATE);
    addConditionalStartBranches();

    Map<String, Object> launchParams = Map.of("route", "A", "businessValue", "retain-me");
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflow.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(workflow.requestId())
        .traceId("trace-" + tenantId)
        .params(launchParams)
        .build();
    Mockito.doAnswer(invocation -> {
          WorkflowRunEntity run = invocation.getArgument(1);
          workflowRunId = run.getId();
          RlsTenantContextHolder.runWithTenant(
              tenantId, () -> workflowRunManagement.pause(tenantId, workflowRunId));
          return 0;
        })
        .when(nodeDispatchService)
        .dispatchNode(
            ArgumentMatchers.isNull(),
            ArgumentMatchers.any(WorkflowRunEntity.class),
            ArgumentMatchers.eq(new WorkflowDagService.DagNodeResolution("JOB", "JOB")),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString());

    RlsTenantContextHolder.runWithTenant(tenantId, () -> launchService.launch(launchRequest));

    assertThat(runStatus()).isEqualTo("PAUSED");
    String protectedStartPayload = jdbc.queryForObject(selectSourcePayloadProtectedSql, String.class, workflowRunId);
    assertThat(protectedStartPayload).isNotBlank().doesNotContain("retain-me");
    assertThat(secretPayloadProtector.reveal(protectedStartPayload))
        .contains("\"route\":\"A\"", "\"businessValue\":\"retain-me\"");

    Mockito.reset(nodeDispatchService);
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> workflowRunManagement.resume(tenantId, workflowRunId));

    assertThat(nodeStatus("JOB")).isEqualTo("READY");
    assertThat(nodeStatus("JOB_B")).isEqualTo("SKIPPED");
    String selectedLaunchPayload =
        jdbc.queryForObject(selectLaunchPayloadSql, String.class, tenantId, workflowRunId);
    assertThat(selectedLaunchPayload).isEqualTo("A");
    String selectedLaunchPayloadRead2 =
        jdbc.queryForObject(selectLaunchPayloadSql2, String.class, tenantId, workflowRunId);
    assertThat(selectedLaunchPayloadRead2).isEqualTo("retain-me");
    Long selectedTriggerRequestCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isZero();
  }

  @Test
  @DisplayName("定义停用后仍按已接纳根运行的冻结快照恢复启动")
  void shouldResumeAcceptedLaunchFromFrozenSnapshotAfterDefinitionDisabled() {
    final String selectIdSql = """
        select wr.id from batch.workflow_run wr
        join batch.trigger_request tr
          on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
        where wr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request
        where tenant_id = ? and parent_workflow_run_id = ?
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and request_id = ?
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled = false
        where tenant_id = ? and id = ?
        """;

    tenantId = "disabled-resume-" + UUID.randomUUID().toString().replace("-", "");
    String workerGroup = "WG-" + tenantId;
    workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, "GENERAL", workerGroup, TriggerType.API, BIZ_DATE);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflow.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(workflow.requestId())
        .traceId("trace-" + tenantId)
        .params(Map.of("businessValue", "accepted-before-disable"))
        .build();

    Mockito.doThrow(new IllegalStateException("simulated process interruption"))
        .when(workflowRunMapper)
        .markRunning(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), tenantId) && java.util.Objects.equals(parameters.runStatus(), "RUNNING") && parameters.currentNodeCode() != null && parameters.startedAt() != null));

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenantId, () -> launchService.launch(launchRequest)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated process interruption");

    workflowRunId = jdbc.queryForObject(selectIdSql, Long.class, tenantId, workflow.requestId());
    assertThat(runStatus()).isEqualTo("CREATED");
    Long selectedTriggerRequestCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isZero();
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenantId, workflow.requestId());
    assertThat(selectedRequestStatus).isEqualTo("ACCEPTED");

    Mockito.reset(workflowRunMapper);
    jdbc.update(updateWorkflowDefinitionSql, tenantId, workflow.workflowDefinitionId());

    String resumedRunId = RlsTenantContextHolder.runWithTenant(
        tenantId, () -> launchService.launch(launchRequest)).instanceNo();

    assertThat(resumedRunId).isEqualTo(workflowRunId.toString());
    assertThat(runStatus()).isEqualTo("RUNNING");
    assertThat(nodeStatus("JOB")).isEqualTo("READY");
    Long selectedTriggerRequestCountRead2 =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
    assertThat(selectedTriggerRequestCountRead2).isEqualTo(1L);
    String selectedRequestStatusRead2 =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenantId, workflow.requestId());
    assertThat(selectedRequestStatusRead2).isEqualTo("LAUNCHED");

    RlsTenantContextHolder.runWithTenant(tenantId, () -> launchService.launch(launchRequest));
    assertThat(nodeRunCount("JOB")).isEqualTo(1);
    Long selectedTriggerRequestCountRead3 =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
    assertThat(selectedTriggerRequestCountRead3).isEqualTo(1L);
  }

  @Test
  @DisplayName("定义停用后重投已接纳 EVENT 启动并结清冻结日历上的既有预期")
  void shouldResumeAcceptedEventAndSatisfyExpectationAfterDefinitionDisabled() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
    """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set external_event_code = ? where tenant_id = ? and request_id = ?
    """;
    final String selectIdSql = """
        select wr.id from batch.workflow_run wr
        join batch.trigger_request tr
          on tr.tenant_id = wr.tenant_id and tr.id = wr.trigger_request_id
        where wr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.expected_work_occurrence
        where tenant_id = ? and calendar_code = 'JOB_CALENDAR'
          and workflow_definition_id = ? and source_type = 'EXTERNAL_EVENT'
          and source_key = ? and biz_date = ?
        """;
    final String updateWorkflowDefinitionSql2 = """
        update batch.workflow_definition set enabled = false
        where tenant_id = ? and id = ?
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and request_id = ?
        """;

    tenantId = "disabled-event-resume-" + UUID.randomUUID().toString().replace("-", "");
    String workerGroup = "WG-" + tenantId;
    String eventCode = "event-" + tenantId;
    workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, "GENERAL", workerGroup, TriggerType.EVENT, BIZ_DATE);
    jdbc.update(
        updateWorkflowDefinitionSql,
        tenantId,
        workflow.workflowDefinitionId());
    jdbc.update(
        updateTriggerRequestSql,
        eventCode,
        tenantId,
        workflow.requestId());
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        "JOB_CALENDAR",
        workflow.workflowDefinitionId(),
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, "JOB_CALENDAR", BIZ_DATE, "UTC"))
        .isOne();

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflow.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .externalEventCode(eventCode)
        .requestId(workflow.requestId())
        .traceId("trace-" + tenantId)
        .params(Map.of("businessValue", "accepted-before-disable"))
        .build();
    Mockito.doThrow(new IllegalStateException("simulated process interruption"))
        .when(workflowRunMapper)
        .markRunning(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), tenantId) && java.util.Objects.equals(parameters.runStatus(), "RUNNING") && parameters.currentNodeCode() != null && parameters.startedAt() != null));

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenantId, () -> launchService.launch(launchRequest)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated process interruption");

    workflowRunId = jdbc.queryForObject(selectIdSql, Long.class, tenantId, workflow.requestId());
    assertThat(runStatus()).isEqualTo("CREATED");
    String selectedOccurrenceStatus = jdbc.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflow.workflowDefinitionId(),
        eventCode,
        BIZ_DATE);
    assertThat(selectedOccurrenceStatus).isEqualTo("EXPECTED");

    Mockito.reset(workflowRunMapper);
    jdbc.update(updateWorkflowDefinitionSql2, tenantId, workflow.workflowDefinitionId());

    String resumedRunId = RlsTenantContextHolder.runWithTenant(
        tenantId, () -> launchService.launch(launchRequest)).instanceNo();

    assertThat(resumedRunId).isEqualTo(workflowRunId.toString());
    assertThat(runStatus()).isEqualTo("RUNNING");
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenantId, workflow.requestId());
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    String selectedOccurrenceStatusRead2 = jdbc.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflow.workflowDefinitionId(),
        eventCode,
        BIZ_DATE);
    assertThat(selectedOccurrenceStatusRead2).isEqualTo("SATISFIED");
  }

  @Test
  @DisplayName("定义停用后拒绝没有既有根运行的新启动")
  void shouldRejectNewLaunchWhenDefinitionIsDisabled() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled = false
        where tenant_id = ? and id = ?
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run
        where tenant_id = ? and workflow_definition_id = ?
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id = ? and request_id = ?
        """;

    tenantId = "disabled-new-" + UUID.randomUUID().toString().replace("-", "");
    String workerGroup = "WG-" + tenantId;
    workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, "GENERAL", workerGroup, TriggerType.API, BIZ_DATE);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflow.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(workflow.requestId())
        .traceId("trace-" + tenantId)
        .params(Map.of())
        .build();
    jdbc.update(updateWorkflowDefinitionSql, tenantId, workflow.workflowDefinitionId());

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenantId, () -> launchService.launch(launchRequest)))
        .isInstanceOf(BizException.class);

    Long selectedWorkflowRunCount =
        jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflow.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isZero();
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenantId, workflow.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
  }

  @AfterEach
  void removeOwnedRows() {
    final String deleteOutboxEventSql = """
        delete from batch.outbox_event where tenant_id=?
    """;
    final String deleteOutboxEventDedupKeySql = """
        delete from batch.outbox_event_dedup_key where tenant_id=?
    """;
    final String deleteExpectedWorkOccurrenceSql = """
        delete from batch.expected_work_occurrence where tenant_id=?
    """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (select id from batch.workflow_run\s\
        where tenant_id=?)\
        """;
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where tenant_id=?
    """;
    final String deleteJobTaskSql = """
        delete from batch.job_task where tenant_id=?
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and parent_workflow_run_id is not null
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id=?
    """;
    final String deleteTriggerRequestSql2 = """
        delete from batch.trigger_request where tenant_id=?
    """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id=?
    """;
    final String deleteWorkflowNodeSql = """
        delete from batch.workflow_node where tenant_id=?
    """;
    final String deleteExternalEventExpectationDefinitionSql = """
        delete from batch.external_event_expectation_definition where tenant_id=?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;
    final String deleteBatchDayInstanceSql = """
        delete from batch.batch_day_instance where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=?
    """;
    final String deleteWorkerRegistrySql = """
        delete from batch.worker_registry where tenant_id=?
    """;

    Mockito.reset(nodeDispatchService, workflowRunMapper);
    if (tenantId == null) {
      return;
    }
    jdbc.update(deleteOutboxEventSql, tenantId);
    jdbc.update(deleteOutboxEventDedupKeySql, tenantId);
    jdbc.update(deleteExpectedWorkOccurrenceSql, tenantId);
    jdbc.update(deleteWorkflowNodeRunSql, tenantId);
    jdbc.update(deleteJobExecutionLogSql, tenantId);
    jdbc.update(deleteJobTaskSql, tenantId);
    jdbc.update(deleteJobPartitionSql, tenantId);
    jdbc.update(deleteJobInstanceSql, tenantId);
    jdbc.update(
        deleteTriggerRequestSql,
        tenantId);
    jdbc.update(deleteWorkflowRunSql, tenantId);
    jdbc.update(deleteTriggerRequestSql2, tenantId);
    jdbc.update(deleteWorkflowEdgeSql, tenantId);
    jdbc.update(deleteWorkflowNodeSql, tenantId);
    jdbc.update(deleteExternalEventExpectationDefinitionSql, tenantId);
    jdbc.update(deleteWorkflowDefinitionSql, tenantId);
    jdbc.update(deleteJobDefinitionSql, tenantId);
    jdbc.update(deleteBatchDayInstanceSql, tenantId);
    jdbc.update(deleteBusinessCalendarSql, tenantId);
    jdbc.update(deleteWorkerRegistrySql, tenantId);
  }

  private void seedPausedFailureWorkflow() {
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id=? and workflow_definition_id=?
    """;

    tenantId = "pause-resume-" + UUID.randomUUID().toString().replace("-", "");
    String workerGroup = "WG-" + tenantId;
    workflow = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenantId, "GENERAL", workerGroup, TriggerType.API, BIZ_DATE);
    addWaitSuccessors();

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(workflow.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(workflow.requestId())
        .traceId("trace-" + tenantId)
        .params(Map.of())
        .build();
    RlsTenantContextHolder.runWithTenant(tenantId, () -> launchService.launch(launchRequest));
    workflowRunId = jdbc.queryForObject(
        selectIdSql,
        Long.class,
        tenantId,
        workflow.workflowDefinitionId());
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> workflowRunManagement.pause(tenantId, workflowRunId));
  }

  private void addWaitSuccessors() {
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order=5 where workflow_definition_id=? and node_code='END'
    """;

    Long workflowDefinitionId = workflow.workflowDefinitionId();
    String waitNodeParams = """
        {"sensor_type":"DB_ROW_EXISTS","sensor_spec":{"schema":"biz","sql":"select 1"},
         "timeout_seconds":3600,"poll_interval_seconds":60,"on_timeout":"FAIL"}
        """;
    jdbc.update(
        updateWorkflowNodeSql,
        workflowDefinitionId);
    insertWaitNode(FAILURE_WAIT, "Failure wait", 2, waitNodeParams);
    insertWaitNode(ALWAYS_WAIT, "Always wait", 3, waitNodeParams);
    insertWaitNode(SUCCESS_WAIT, "Success wait", 4, waitNodeParams);
    addEdge("JOB", FAILURE_WAIT, "FAILURE");
    addEdge("JOB", ALWAYS_WAIT, "ALWAYS");
    addEdge("JOB", SUCCESS_WAIT, "SUCCESS");
    addEdge(FAILURE_WAIT, "END", "ALWAYS");
    addEdge(ALWAYS_WAIT, "END", "ALWAYS");
    addEdge(SUCCESS_WAIT, "END", "ALWAYS");
  }

  private void addConditionalStartBranches() {
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order=3 where workflow_definition_id=? and node_code='END'
    """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'JOB_B', 'Job B', 'JOB', ?, 2, 'NONE', 0, 0, true)
        """;
    final String updateWorkflowEdgeSql = """
        update batch.workflow_edge
        set edge_type='CONDITION', condition_expr=?
        where workflow_definition_id=? and from_node_code='START' and to_node_code='JOB'
        """;

    Long workflowDefinitionId = workflow.workflowDefinitionId();
    jdbc.update(
        updateWorkflowNodeSql,
        workflowDefinitionId);
    jdbc.update(insertWorkflowNodeSql,
        tenantId,
        workflowDefinitionId,
        workflow.jobCode());
    jdbc.update(updateWorkflowEdgeSql, "route = 'A'", workflowDefinitionId);
    addConditionalEdge("START", "JOB_B", "route = 'B'");
    addEdge("JOB_B", "END", "SUCCESS");
  }

  private void insertWaitNode(String nodeCode, String nodeName, int nodeOrder, String params) {
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            node_params, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, ?, ?, 'WAIT', ?::jsonb, ?, 'NONE', 0, 0, true)
        """;

    jdbc.update(insertWorkflowNodeSql,
        tenantId,
        workflow.workflowDefinitionId(),
        nodeCode,
        nodeName,
        params,
        nodeOrder);
  }

  private void addEdge(String fromNodeCode, String toNodeCode, String edgeType) {
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, ?, ?, ?, ?, true)
        """;

    jdbc.update(insertWorkflowEdgeSql,
        tenantId,
        workflow.workflowDefinitionId(),
        fromNodeCode,
        toNodeCode,
        edgeType);
  }

  private void addConditionalEdge(String fromNodeCode, String toNodeCode, String condition) {
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code,
            edge_type, condition_expr, enabled
        ) values (?, ?, ?, ?, 'CONDITION', ?, true)
        """;

    jdbc.update(insertWorkflowEdgeSql,
        tenantId,
        workflow.workflowDefinitionId(),
        fromNodeCode,
        toNodeCode,
        condition);
  }

  private void reportFailureWhilePaused() {
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run
        where workflow_run_id=? and node_code='JOB' order by run_seq desc limit 1
        """;
    final String selectIdSql = """
        select id from batch.job_task where tenant_id=? and job_instance_id=? order by id limit 1
    """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status='RUNNING', started_at=? where tenant_id=? and id=?
    """;

    Long jobInstanceId = jdbc.queryForObject(selectJobInstanceIdSql, Long.class, workflowRunId);
    Long taskId = jdbc.queryForObject(
        selectIdSql,
        Long.class,
        tenantId,
        jobInstanceId);
    jdbc.update(
        updateJobTaskSql,
        Timestamp.from(BatchDateTimeSupport.utcNow()),
        tenantId,
        taskId);
    TaskOutcomeCommand failure = TaskOutcomeCommand.builder()
        .tenantId(tenantId)
        .taskId(taskId)
        .success(false)
        .errorCode("ERR_PAUSED_REPORT")
        .errorMessage("task failed while workflow was paused")
        .resultSummary("{}")
        .build();
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> taskOutcomeService.applyTaskOutcome(failure));
  }

  private void failResumeAfterFirstSuccessorDispatch() {
    Mockito.doThrow(new IllegalStateException("simulated resume process failure"))
        .when(nodeDispatchService)
        .dispatchNode(
            ArgumentMatchers.isNull(),
            ArgumentMatchers.any(),
            ArgumentMatchers.eq(new WorkflowDagService.DagNodeResolution(ALWAYS_WAIT, "WAIT")),
            ArgumentMatchers.nullable(String.class),
            ArgumentMatchers.anyString());
    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenantId, () -> workflowRunManagement.resume(tenantId, workflowRunId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated resume process failure");
  }

  private void pauseAndResume() {
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> workflowRunManagement.pause(tenantId, workflowRunId));
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> workflowRunManagement.resume(tenantId, workflowRunId));
  }

  private String runStatus() {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;

    return jdbc.queryForObject(
        selectRunStatusSql,
        String.class,
        tenantId,
        workflowRunId);
  }

  private String nodeStatus(String nodeCode) {
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run
        where workflow_run_id=? and node_code=? order by run_seq desc limit 1
        """;

    return jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId, nodeCode);
  }

  private long nodeRunCount(String nodeCode) {
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id=? and node_code=?
    """;

    return jdbc.queryForObject(
        selectCountSql,
        Long.class,
        workflowRunId,
        nodeCode);
  }
}
