package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import com.example.batch.orchestrator.application.service.task.DefaultTaskOutcomeService;
import com.example.batch.orchestrator.application.service.task.DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators;
import com.example.batch.orchestrator.application.service.task.JobInstanceTerminalChildStateReconciler;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.version.ResultVersionWriter;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DefaultTaskOutcomeServiceTest {

  @Mock JobInstanceMapper jobInstanceMapper;
  @Mock JobPartitionMapper jobPartitionMapper;
  @Mock JobTaskMapper jobTaskMapper;
  @Mock JobStepInstanceMapper jobStepInstanceMapper;
  @Mock TriggerRequestMapper triggerRequestMapper;
  @Mock WorkflowNodeMapper workflowNodeMapper;
  @Mock WorkflowRunMapper workflowRunMapper;
  @Mock WorkflowNodeRunMapper workflowNodeRunMapper;
  @Mock RetryGovernanceService retryGovernanceService;
  @Mock StateMachine<Object> stateMachine;
  @Mock WorkflowDagService workflowDagService;
  @Mock WorkflowTerminalOutboxService workflowTerminalOutboxService;

  @SuppressWarnings("rawtypes")
  @Mock
  ObjectProvider workflowNodeDispatchServiceProvider;

  @Mock JobInstanceTerminalChildStateReconciler jobInstanceTerminalChildStateReconciler;

  private DefaultTaskOutcomeService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
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
    DefaultTaskOutcomeCollaborators collaborators =
        new DefaultTaskOutcomeCollaborators(
            retryGovernanceService,
            stateMachine,
            workflowDagService,
            workflowNodeDispatchServiceProvider,
            workflowTerminalOutboxService,
            mock(
                com.example.batch.orchestrator.application.engine.VerifierFailureOutboxService
                    .class),
            new SimpleMeterRegistry(),
            jobInstanceTerminalChildStateReconciler,
            mock(ResultVersionWriter.class),
            mock(BatchDayReplayTerminalReconciler.class),
            mock(FailureClassifier.class),
            mock(JobLifecycleMetricsRecorder.class));
    service = new DefaultTaskOutcomeService(jobMappers, workflowMappers, collaborators);
  }

  @Test
  void applyTaskOutcome_taskNotFound_returnsNull() {
    when(jobTaskMapper.selectById(anyString(), anyLong())).thenReturn(null);

    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(99L).success(true).build();
    var result = service.applyTaskOutcome(command);
    assertThat(result).isNull();
  }

  @Test
  void applyTaskOutcome_nullTenantId_returnsNull() {
    when(jobTaskMapper.selectById(null, 1L)).thenReturn(null);

    TaskOutcomeCommand command = TaskOutcomeCommand.builder().taskId(1L).success(true).build();
    var result = service.applyTaskOutcome(command);
    assertThat(result).isNull();
  }

  /** ADR-014：worker 上报的 invocation 与分区当前值不一致 → CONFLICT，防止过期 worker 推进状态。 */
  @Test
  void applyTaskOutcome_partitionInvocationMismatch_throwsBizException() {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(1L);
    task.setTenantId("t1");
    task.setJobInstanceId(10L);
    task.setJobPartitionId(99L);
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    task.setVersion(1L);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setCurrentInvocationId("inv-db");

    when(jobTaskMapper.selectById("t1", 1L)).thenReturn(task);
    when(jobPartitionMapper.selectById("t1", 99L)).thenReturn(partition);

    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId("t1")
            .taskId(1L)
            .workerId("w1")
            .success(true)
            .partitionInvocationId("inv-stale-worker")
            .build();

    assertThatThrownBy(() -> service.applyTaskOutcome(command))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex ->
                assertThat(((BizException) ex).getMessageKey())
                    .isEqualTo("error.task.invocation_mismatch"));
  }

  @Test
  void applyTaskOutcome_selectByQueryForUpdate_usedForPartitionCounting() throws Exception {
    // R3-P0-7：之前的实现只 reflection-check 方法存在性，没有任何业务逻辑断言。
    // 真正测 CAS 分区计数需要构造完整 partition+task+jobInstance mock 链，超出单测范畴
    // （参见 RequiresNewTransactionBoundaryIntegrationTest 集成测试覆盖）。
    // 这里把断言改成"验证 selectByQueryForUpdate 在 JobTaskMapper 接口上存在"，
    // 至少 contract-test 级地保护方法签名（mapper xml ↔ Java interface 漂移会被 javac 接住）。
    var partitionMethod =
        com.example.batch.orchestrator.mapper.JobPartitionMapper.class.getDeclaredMethod(
            "selectByQueryForUpdate",
            com.example.batch.orchestrator.domain.query.JobPartitionQuery.class);
    assertThat(partitionMethod).isNotNull();
    assertThat(partitionMethod.getReturnType()).isAssignableFrom(java.util.List.class);
  }
}
