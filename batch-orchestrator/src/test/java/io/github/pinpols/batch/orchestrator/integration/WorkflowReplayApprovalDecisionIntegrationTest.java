package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplayPreviewService;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySnapshotResolver;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.event.OutboxDomainEventPublisher;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("整Workflow Replay审批决策与请求/Outbox的真实PostgreSQL事务边界")
class WorkflowReplayApprovalDecisionIntegrationTest extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final String APPROVER = "b192-persisted-approver";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 20);

  @Autowired
  private WorkflowRunReplayPreviewService replayService;

  @Autowired
  private ApprovalWorkflowService approvalService;

  @Autowired
  private JdbcTemplate jdbc;

  @MockitoBean
  private WorkflowRunReplaySnapshotResolver snapshotResolver;

  @MockitoSpyBean
  private OutboxDomainEventPublisher publisher;

  private final List<ReplayApprovalFixture> fixtures = new ArrayList<>();

  @ParameterizedTest
  @CsvSource({"REJECTED,approve", "APPROVED,reject"})
  @DisplayName("真实持久决策与操作方向不符时，operation不变且没有请求、事件或幂等键")
  void shouldKeepOperationPending_whenPersistedDecisionDisagrees(String decision, String action) {
    ReplayApprovalFixture fixture = prepareFixture();
    String approvalNo = persistDecision(fixture, decision);

    assertThatThrownBy(() -> {
          if ("approve".equals(action))
            replayService.approve(TENANT, fixture.operationId(), "attempt-actor");
          else replayService.reject(TENANT, fixture.operationId());
        })
        .isInstanceOf(BizException.class);

    assertPendingOperation(fixture);
    assertLaunchCounts(fixture, 0);
    ApprovalWorkflowService.ApprovalRecord retainedDecision =
        approvalService.get(TENANT, approvalNo);
    assertThat(retainedDecision.approvalStatus()).isEqualTo(decision);
    verifyNoInteractions(snapshotResolver);
  }

  @Test
  @DisplayName("批准只产生一个稳定请求及Outbox，其他管理员重试仍使用实际批准人")
  void shouldStartOneLaunchAndPreserveApprover_whenApprovedOperationIsRetried() {
    ReplayApprovalFixture fixture = prepareFixture();
    String approvalNo = persistDecision(fixture, "APPROVED");
    configureFrozenSnapshot(fixture);

    ReplayOperationEntity first =
        replayService.approve(TENANT, fixture.operationId(), "attempt-actor");
    ReplayOperationEntity retried =
        replayService.approve(TENANT, fixture.operationId(), "another-admin");

    assertThat(first.status()).isEqualTo("RUNNING");
    assertThat(first.approvedBy()).isEqualTo(APPROVER);
    assertThat(retried.targetRequestId()).isEqualTo(first.targetRequestId());
    assertThat(retried.approvedBy()).isEqualTo(APPROVER);
    assertLaunchCounts(fixture, 1);
    ApprovalWorkflowService.ApprovalRecord retainedDecision =
        approvalService.get(TENANT, approvalNo);
    assertThat(retainedDecision.approvalStatus()).isEqualTo("APPROVED");
    assertThat(retainedDecision.approverId()).isEqualTo(APPROVER);
    String selectSourceStatusSql =
        "select run_status from batch.workflow_run where tenant_id=? and id=?";
    String sourceStatus =
        jdbc.queryForObject(selectSourceStatusSql, String.class, TENANT, fixture.sourceRunId());
    assertThat(sourceStatus).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Outbox真实写入后失败时回滚operation、请求、事件和幂等键，同审批可再次批准")
  void shouldRollbackAllLaunchWrites_whenFailureFollowsOutboxInsert() {
    ReplayApprovalFixture fixture = prepareFixture();
    String approvalNo = persistDecision(fixture, "APPROVED");
    configureFrozenSnapshot(fixture);
    OutboxDomainEventPublisher publisherTarget = AopTestUtils.getUltimateTargetObject(publisher);
    doAnswer(invocation -> {
          DomainEvent event = invocation.getArgument(0);
          Object eventId = invocation.callRealMethod();
          if (fixture.eventKey().equals(event.eventKey())) {
            throw new IllegalStateException("injected failure after owned Outbox insert");
          }
          return eventId;
        })
        .when(publisherTarget)
        .publish(any(DomainEvent.class));

    assertThatThrownBy(() -> replayService.approve(TENANT, fixture.operationId(), "attempt-actor"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure after owned Outbox insert");

    assertPendingOperation(fixture);
    assertLaunchCounts(fixture, 0);
    ApprovalWorkflowService.ApprovalRecord retainedDecision =
        approvalService.get(TENANT, approvalNo);
    assertThat(retainedDecision.approvalStatus()).isEqualTo("APPROVED");
    doCallRealMethod().when(publisherTarget).publish(any(DomainEvent.class));
    ReplayOperationEntity retried =
        replayService.approve(TENANT, fixture.operationId(), "retry-actor");
    assertThat(retried.status()).isEqualTo("RUNNING");
    assertThat(retried.approvedBy()).isEqualTo(APPROVER);
    assertLaunchCounts(fixture, 1);
  }

  private ReplayApprovalFixture prepareFixture() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, TENANT, "GENERAL", "B192_APPROVAL", TriggerType.MANUAL, BIZ_DATE);
    WorkflowChildTriggerSeed source = LaunchIntegrationFixture.prepareWorkflowChildTriggerRequest(
        jdbc, TENANT, seed, BIZ_DATE, TriggerType.MANUAL);
    String operationKey = UUID.randomUUID().toString();
    ReplayApprovalFixture pending =
        new ReplayApprovalFixture(seed, source.workflowRunId(), null, operationKey);
    fixtures.add(pending);
    String failSourceSql =
        "update batch.workflow_run set run_status='FAILED',finished_at=current_timestamp where tenant_id=? and id=?";
    jdbc.update(failSourceSql, TENANT, source.workflowRunId());
    String insertOperationSql = """
        insert into batch.replay_operation (
            tenant_id,operation_key,operation_type,status,scope,execution_mode,candidate_source,
            source_workflow_run_id,request_hash,snapshot_hash,snapshot_payload,reason,requested_by,
            total_count,in_flight_count)
        values (?,?::uuid,'WORKFLOW_RUN','PENDING_APPROVAL','FULL_WORKFLOW','REPLAY',
            'EXACT_SOURCE_WORKFLOW_RUN',?,'b192-request','b192-snapshot','{}'::jsonb,
            'verify persisted approval decision','b192-requester',1,1)
        returning id
        """;
    Long operationId = jdbc.queryForObject(
        insertOperationSql, Long.class, TENANT, operationKey, source.workflowRunId());
    ReplayApprovalFixture fixture =
        new ReplayApprovalFixture(seed, source.workflowRunId(), operationId, operationKey);
    fixtures.set(fixtures.size() - 1, fixture);
    return fixture;
  }

  private String persistDecision(ReplayApprovalFixture fixture, String status) {
    Map<String, Object> payload =
        Map.of("tenantId", TENANT, "replayOperationId", fixture.operationId());
    String payloadJson = JsonUtils.toJson(payload);
    String sourceRunId = String.valueOf(fixture.sourceRunId());
    ApprovalWorkflowService.ApprovalTarget target = new ApprovalWorkflowService.ApprovalTarget(
        "COMPENSATION", "WORKFLOW_RUN_REPLAY", "WORKFLOW_RUN", sourceRunId, payloadJson);
    ApprovalWorkflowService.ApprovalSource source = new ApprovalWorkflowService.ApprovalSource(
        "b192-requester",
        "b192-approval-trace",
        fixture.operationKey(),
        "verify decision boundary");
    ApprovalWorkflowService.ApprovalSubmitCommand command =
        ApprovalWorkflowService.ApprovalSubmitCommand.of(TENANT, target, source);
    String approvalNo = approvalService.submit(command);
    if ("APPROVED".equals(status))
      approvalService.approve(TENANT, approvalNo, APPROVER, "approve once");
    else approvalService.reject(TENANT, approvalNo, APPROVER, "reject once");
    return approvalNo;
  }

  private void configureFrozenSnapshot(ReplayApprovalFixture fixture) {
    Long workflowDefinitionId = fixture.seed().workflowDefinitionId();
    Map<String, Object> rootParams = Map.of();
    WorkflowRunReplaySnapshotResolver.Snapshot snapshot =
        new WorkflowRunReplaySnapshotResolver.Snapshot(
            TENANT,
            workflowDefinitionId,
            1,
            "JOB_CALENDAR",
            BIZ_DATE,
            null,
            null,
            false,
            null,
            rootParams,
            "{}");
    String requestId = fixture.requestId();
    when(snapshotResolver.resolve(any(ReplayOperationEntity.class), eq(requestId)))
        .thenReturn(snapshot);
  }

  private void assertPendingOperation(ReplayApprovalFixture fixture) {
    String selectOperationSql =
        "select status,target_request_id,approved_by from batch.replay_operation where tenant_id=? and id=?";
    Map<String, Object> operation =
        jdbc.queryForMap(selectOperationSql, TENANT, fixture.operationId());
    assertThat(operation).containsEntry("status", "PENDING_APPROVAL");
    assertThat(operation.get("target_request_id")).isNull();
    assertThat(operation.get("approved_by")).isNull();
  }

  private void assertLaunchCounts(ReplayApprovalFixture fixture, int expected) {
    String countRequestSql =
        "select count(*) from batch.trigger_request where tenant_id=? and request_id=?";
    String countOutboxSql =
        "select count(*) from batch.outbox_event where tenant_id=? and event_key=?";
    String countDedupSql =
        "select count(*) from batch.outbox_event_dedup_key where tenant_id=? and event_key=?";
    Integer requests =
        jdbc.queryForObject(countRequestSql, Integer.class, TENANT, fixture.requestId());
    Integer events = jdbc.queryForObject(countOutboxSql, Integer.class, TENANT, fixture.eventKey());
    Integer dedupKeys =
        jdbc.queryForObject(countDedupSql, Integer.class, TENANT, fixture.eventKey());
    assertThat(requests).isEqualTo(expected);
    assertThat(events).isEqualTo(expected);
    assertThat(dedupKeys).isEqualTo(expected);
  }

  @AfterEach
  void cleanupOwnedFixtures() {
    for (ReplayApprovalFixture fixture : fixtures) {
      String eventKey = fixture.eventKey();
      List<String> deleteOutboxSql = List.of(
          "delete from batch.event_delivery_log where tenant_id=? and event_key=?",
          "delete from batch.event_outbox_retry where tenant_id=? and event_key=?",
          "delete from batch.outbox_event where tenant_id=? and event_key=?",
          "delete from batch.outbox_event_dedup_key where tenant_id=? and event_key=?");
      for (String sql : deleteOutboxSql) jdbc.update(sql, TENANT, eventKey);
      String deleteOperationSql =
          "delete from batch.replay_operation where tenant_id=? and operation_key=?::uuid";
      String deleteApprovalSql =
          "delete from batch.approval_command where tenant_id=? and source_idempotency_key=?";
      jdbc.update(deleteOperationSql, TENANT, fixture.operationKey());
      jdbc.update(deleteApprovalSql, TENANT, fixture.operationKey());
      Long definitionId = fixture.seed().workflowDefinitionId();
      String deleteRunSql =
          "delete from batch.workflow_run where tenant_id=? and workflow_definition_id=?";
      String deleteRequestsSql =
          "delete from batch.trigger_request where tenant_id=? and (workflow_definition_id=? or job_code=?)";
      jdbc.update(deleteRunSql, TENANT, definitionId);
      jdbc.update(deleteRequestsSql, TENANT, definitionId, fixture.seed().jobCode());
      String deleteEdgesSql =
          "delete from batch.workflow_edge where tenant_id=? and workflow_definition_id=?";
      String deleteNodesSql =
          "delete from batch.workflow_node where tenant_id=? and workflow_definition_id=?";
      String deleteDefinitionSql =
          "delete from batch.workflow_definition where tenant_id=? and id=?";
      String deleteJobSql = "delete from batch.job_definition where tenant_id=? and job_code=?";
      jdbc.update(deleteEdgesSql, TENANT, definitionId);
      jdbc.update(deleteNodesSql, TENANT, definitionId);
      jdbc.update(deleteDefinitionSql, TENANT, definitionId);
      jdbc.update(deleteJobSql, TENANT, fixture.seed().jobCode());
    }
    fixtures.clear();
  }

  private record ReplayApprovalFixture(
      WorkflowLaunchSeed seed, Long sourceRunId, Long operationId, String operationKey) {
    private String requestId() {
      return "workflow-replay-" + operationId;
    }

    private String eventKey() {
      return TENANT + ":workflow-replay:" + operationId + ":launch";
    }
  }
}
