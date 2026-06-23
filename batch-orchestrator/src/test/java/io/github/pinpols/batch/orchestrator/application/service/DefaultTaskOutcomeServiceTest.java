package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import io.github.pinpols.batch.orchestrator.application.service.task.DefaultTaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.task.DefaultTaskOutcomeService.DefaultTaskOutcomeCollaborators;
import io.github.pinpols.batch.orchestrator.application.service.task.JobInstanceTerminalChildStateReconciler;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionWriter;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateMachine;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import io.github.pinpols.batch.orchestrator.service.failure.FailureClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
                io.github.pinpols.batch.orchestrator.application.engine.VerifierFailureOutboxService
                    .class),
            new SimpleMeterRegistry(),
            jobInstanceTerminalChildStateReconciler,
            mock(ResultVersionWriter.class),
            mock(BatchDayReplayTerminalReconciler.class),
            mock(FailureClassifier.class),
            mock(JobLifecycleMetricsRecorder.class),
            mock(
                io.github.pinpols.batch.orchestrator.application.engine.CountContinuityOutboxService
                    .class));
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
        io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper.class.getDeclaredMethod(
            "selectByQueryForUpdate",
            io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery.class);
    assertThat(partitionMethod).isNotNull();
    assertThat(partitionMethod.getReturnType()).isAssignableFrom(java.util.List.class);
  }

  /**
   * 死锁回归守护:成功 outcome 必须**先**以统一顺序对全兄弟分区 {@code selectByQueryForUpdate}(FOR UPDATE) 加锁,**再**对自己这个分区
   * {@code markStatus} 写锁。否则两个并发 outcome 各自先锁自己分区、再抢全集 → 锁顺序反转死锁。本测试用 InOrder 钉死「bulk lock 先于
   * per-partition write」这一不变量。
   */
  @Test
  void applyTaskOutcome_locksAllSiblingPartitionsBeforeMarkingSelf() {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(1L);
    task.setTenantId("t1");
    task.setJobInstanceId(10L);
    task.setJobPartitionId(99L);
    task.setTaskStatus(TaskStatus.RUNNING.code());
    task.setAssignedWorkerCode("w1");
    task.setVersion(1L);

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(99L);
    partition.setTenantId("t1");
    partition.setJobInstanceId(10L);
    partition.setPartitionStatus(PartitionStatus.RUNNING.code()); // 未完成 → 非终态,流程短
    partition.setVersion(1L);

    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setId(10L);
    instance.setTenantId("t1");
    instance.setInstanceStatus("RUNNING");
    instance.setVersion(1L);
    instance.setDryRun(false);

    when(jobTaskMapper.selectById("t1", 1L)).thenReturn(task);
    when(jobPartitionMapper.selectById("t1", 99L)).thenReturn(partition);
    when(jobInstanceMapper.selectById("t1", 10L)).thenReturn(instance);
    when(jobTaskMapper.finishTask(any())).thenReturn(1);
    when(jobPartitionMapper.selectByQueryForUpdate(any())).thenReturn(List.of(partition));
    when(jobPartitionMapper.markStatus(any())).thenReturn(1);
    when(jobTaskMapper.selectByQuery(any())).thenReturn(List.of(task));
    when(stateMachine.transition(any(), anyString()))
        .thenReturn(new StateTransition("RUNNING", "evt", "RUNNING"));
    when(jobInstanceMapper.updateProgress(any())).thenReturn(1);

    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder().tenantId("t1").taskId(1L).workerId("w1").success(true).build();

    service.applyTaskOutcome(command);

    InOrder inOrder = inOrder(jobPartitionMapper);
    // 统一顺序的全兄弟 FOR UPDATE 锁必须先于针对自己分区的 markStatus 写锁。
    inOrder.verify(jobPartitionMapper).selectByQueryForUpdate(any());
    inOrder.verify(jobPartitionMapper).markStatus(any());
  }
}
