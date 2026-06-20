package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.orchestrator.application.engine.CountContinuityOutboxService;
import com.example.batch.orchestrator.application.engine.VerifierFailureOutboxService;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import com.example.batch.orchestrator.application.service.version.ResultVersionWriter;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import com.example.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * 守护 DefaultTaskOutcomeService 的 workflow_node_run 并发幂等语义:
 *
 * <ul>
 *   <li>recordNodeRunReady/Start: 唯一约束冲突 → 不抛错,返回已有记录(防止两个 outcome 同时 insert 同一节点)
 *   <li>runSeq 递增: 第二次 record 同节点应拿到 +1 的 runSeq
 * </ul>
 *
 * <p>applyTaskOutcome 主流程涉及 11 个 collaborator + 复杂状态机分支,留给 Testcontainers 集成测覆盖。
 */
class DefaultTaskOutcomeServiceTest {

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private WorkflowNodeMapper workflowNodeMapper;
  @Mock private WorkflowRunMapper workflowRunMapper;
  @Mock private WorkflowNodeRunMapper workflowNodeRunMapper;

  @Mock private RetryGovernanceService retryGovernanceService;
  @Mock private StateMachine<Object> stateMachine;
  @Mock private WorkflowDagService workflowDagService;
  @Mock private ObjectProvider<WorkflowNodeDispatchService> nodeDispatchProvider;
  @Mock private WorkflowTerminalOutboxService workflowTerminalOutboxService;
  @Mock private VerifierFailureOutboxService verifierFailureOutboxService;
  @Mock private JobInstanceTerminalChildStateReconciler terminalChildReconciler;
  @Mock private ResultVersionWriter resultVersionWriter;
  @Mock private BatchDayReplayTerminalReconciler batchDayReplayReconciler;
  @Mock private FailureClassifier failureClassifier;
  @Mock private JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder;

  private DefaultTaskOutcomeService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    OrchestratorJobMappers jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper);
    OrchestratorWorkflowMappers workflowMappers =
        new OrchestratorWorkflowMappers(
            workflowNodeMapper, workflowRunMapper, workflowNodeRunMapper);
    DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators collaborators =
        new DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators(
            retryGovernanceService,
            stateMachine,
            workflowDagService,
            nodeDispatchProvider,
            workflowTerminalOutboxService,
            verifierFailureOutboxService,
            meterRegistry,
            terminalChildReconciler,
            resultVersionWriter,
            batchDayReplayReconciler,
            failureClassifier,
            jobLifecycleMetricsRecorder,
            mock(CountContinuityOutboxService.class));
    service = new DefaultTaskOutcomeService(jobMappers, workflowMappers, collaborators);
  }

  // ===== recordNodeRunReady =====

  @Test
  @DisplayName("recordNodeRunReady: 首次插入 → runSeq=1,状态 READY")
  void readyFirstInsertSetsRunSeq1() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(eq(10L), eq("n1")))
        .thenReturn(null);

    WorkflowNodeRunEntity result = service.recordNodeRunReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(1);
    assertThat(result.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.READY.code());
    assertThat(result.getRetryCount()).isZero();
    verify(workflowNodeRunMapper).insert(any());
  }

  @Test
  @DisplayName("recordNodeRunReady: 已有 runSeq=2 → 新 runSeq=3")
  void readySubsequentInsertsIncrementRunSeq() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setRunSeq(2);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(eq(10L), eq("n1")))
        .thenReturn(existing);

    WorkflowNodeRunEntity result = service.recordNodeRunReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(3);
  }

  @Test
  @DisplayName("recordNodeRunReady: 并发 insert 撞唯一约束 → 不抛错,返回已有记录")
  void readyHandlesDuplicateKey() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(eq(10L), eq("n1")))
        .thenReturn(null) // nextRunSeq 读
        .thenReturn(existingRunSeq(5)); // catch block 重读
    org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_conflict"))
        .when(workflowNodeRunMapper)
        .insert(any());

    WorkflowNodeRunEntity result = service.recordNodeRunReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(5);
    verify(workflowNodeRunMapper, times(1)).insert(any());
  }

  // ===== recordNodeRunStart =====

  @Test
  @DisplayName("recordNodeRunStart: 首次插入 → 状态 RUNNING + startedAt 透传")
  void startFirstInsertRunningWithStartedAt() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(eq(10L), eq("n1")))
        .thenReturn(null);

    java.time.Instant t = java.time.Instant.parse("2026-05-20T10:00:00Z");
    WorkflowNodeRunEntity result = service.recordNodeRunStart(10L, "n1", "TASK", t);

    assertThat(result.getRunSeq()).isEqualTo(1);
    assertThat(result.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.RUNNING.code());
    assertThat(result.getStartedAt()).isEqualTo(t);
  }

  @Test
  @DisplayName("recordNodeRunStart: 并发 insert 撞唯一约束 → 返回已有记录,不抛")
  void startHandlesDuplicateKey() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(eq(10L), eq("n1")))
        .thenReturn(null)
        .thenReturn(existingRunSeq(2));
    org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_conflict"))
        .when(workflowNodeRunMapper)
        .insert(any());

    WorkflowNodeRunEntity result =
        service.recordNodeRunStart(10L, "n1", "TASK", java.time.Instant.now());

    assertThat(result.getRunSeq()).isEqualTo(2);
  }

  @Test
  @DisplayName("recordNodeRunStart: insert 的 entity 字段都齐(nodeCode/nodeType/runSeq)")
  void startPersistsFullEntity() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(anyLong(), any()))
        .thenReturn(null);

    service.recordNodeRunStart(10L, "n1", "GATEWAY", java.time.Instant.now());

    ArgumentCaptor<WorkflowNodeRunEntity> cap =
        ArgumentCaptor.forClass(WorkflowNodeRunEntity.class);
    verify(workflowNodeRunMapper).insert(cap.capture());
    WorkflowNodeRunEntity inserted = cap.getValue();
    assertThat(inserted.getWorkflowRunId()).isEqualTo(10L);
    assertThat(inserted.getNodeCode()).isEqualTo("n1");
    assertThat(inserted.getNodeType()).isEqualTo("GATEWAY");
    assertThat(inserted.getRunSeq()).isEqualTo(1);
    assertThat(inserted.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.RUNNING.code());
  }

  private WorkflowNodeRunEntity existingRunSeq(int seq) {
    WorkflowNodeRunEntity e = new WorkflowNodeRunEntity();
    e.setRunSeq(seq);
    e.setNodeCode("n1");
    return e;
  }
}
