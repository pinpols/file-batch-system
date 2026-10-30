package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.orchestrator.application.engine.JobTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetrics;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 守护 job_instance 终态 CAS + 子表收敛的事务原子语义:
 *
 * <ul>
 *   <li>CAS 命中(rows > 0): 必须调 reconcileChildren(同事务保证子表跟进) + afterCommit 上报 metrics
 *   <li>CAS miss(rows == 0): 不能调 reconcileChildren,也不上报 metrics(避免污染)
 * </ul>
 */
@DisplayName("作业实例终态应用服务: 终态条件更新与子表收敛口径")
@ExtendWith(MockitoExtension.class)
class JobInstanceTerminalStatusApplicationServiceTest {

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobDefinitionMapper jobDefinitionMapper;

  @Mock
  private JobInstanceTerminalChildStateReconciler reconciler;

  @Mock
  private TimedOutWorkflowNodeProgression timedOutWorkflowNodeProgression;

  @Mock
  private JobLifecycleMetrics jobLifecycleMetrics;

  private JobLifecycleMetricsRecorder recorder;
  private JobInstanceTerminalStatusApplicationService service;

  @BeforeEach
  void setUp() {
    recorder = new JobLifecycleMetricsRecorder(
        jobInstanceMapper, jobDefinitionMapper, jobLifecycleMetrics);
    service = new JobInstanceTerminalStatusApplicationService(
        Mockito.mock(JobTerminalOutboxService.class),
        jobInstanceMapper,
        reconciler,
        recorder,
        timedOutWorkflowNodeProgression);
  }

  private JobInstanceTerminalStatusCommand cmd(String terminal) {
    return new JobInstanceTerminalStatusCommand("ta", 100L, terminal, Instant.now(), 5L);
  }

  @Test
  @DisplayName("CAS 成功(rows=1) → 触发 reconcile,返 1")
  void shouldReconcileChildren_whenTerminalUpdateHits() {
    when(jobInstanceMapper.updateStatus(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.tenantId() != null
            && parameters.instanceStatus() != null)))
        .thenReturn(1);
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setCreatedAt(Instant.parse("2026-05-21T00:00:00Z"));
    instance.setJobDefinitionId(200L);
    when(jobInstanceMapper.selectById("ta", 100L)).thenReturn(instance);
    when(jobDefinitionMapper.selectById(200L))
        .thenReturn(JobDefinitionEntity.builder().jobType("IMPORT").build());

    TransactionSynchronizationManager.initSynchronization();
    try {
      int rows =
          service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.FAILED.code()));

      assertThat(rows).isEqualTo(1);
      var order = inOrder(jobInstanceMapper, reconciler);
      order.verify(jobInstanceMapper).acquireInstanceAdvisoryLock("ta", 100L);
      order
          .verify(jobInstanceMapper)
          .updateStatus(ArgumentMatchers.argThat(parameters -> parameters != null
              && Objects.equals(parameters.tenantId(), "ta")
              && Objects.equals(parameters.id(), 100L)
              && parameters.instanceStatus() != null
              && Objects.equals(parameters.expectedVersion(), 5L)));
      order.verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.FAILED.code());
      verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.FAILED.code());
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
      verify(jobLifecycleMetrics)
          .recordCompletion(
              eq("ta"),
              eq("IMPORT"),
              eq(JobInstanceStatus.FAILED.code()),
              eq(false),
              any(Duration.class));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("CAS miss(rows=0) → 不触发 reconcile,返 0(避免抹掉并发结果)")
  void shouldSkipReconcile_whenTerminalUpdateMisses() {
    when(jobInstanceMapper.updateStatus(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.tenantId() != null
            && parameters.instanceStatus() != null)))
        .thenReturn(0);

    int rows =
        service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.SUCCESS.code()));

    assertThat(rows).isZero();
    verify(reconciler, never()).reconcile(anyString(), anyLong(), anyString());
    verify(jobLifecycleMetrics, never())
        .recordCompletion(anyString(), anyString(), anyString(), anyBoolean(), any(Duration.class));
  }

  @Test
  @DisplayName("超时终态 CAS 成功后在同一应用事务推进所属 Workflow")
  void shouldAdvanceWorkflowAfterTimedOutInstanceIsTerminalized() {
    when(jobInstanceMapper.updateStatusWhenTaskRunning(
            ArgumentMatchers.argThat(parameters -> parameters != null
                && parameters.tenantId() != null
                && parameters.instanceStatus() != null)))
        .thenReturn(1);
    JobInstanceTerminalStatusCommand command = cmd(JobInstanceStatus.FAILED.code());

    int rows = service.enforceTimeoutAndReconcileWorkflow(command);

    assertThat(rows).isEqualTo(1);
    verify(timedOutWorkflowNodeProgression)
        .advanceTimedOutWorkflowNode(command.tenantId(), command.id(), command.finishedAt());
  }

  @Test
  @DisplayName("超时终态 CAS 未命中时不推进 Workflow")
  void shouldNotAdvanceWorkflowAfterTimeoutCasMisses() {
    when(jobInstanceMapper.updateStatusWhenTaskRunning(
            ArgumentMatchers.argThat(parameters -> parameters != null
                && parameters.tenantId() != null
                && parameters.instanceStatus() != null)))
        .thenReturn(0);

    int rows = service.enforceTimeoutAndReconcileWorkflow(cmd(JobInstanceStatus.FAILED.code()));

    assertThat(rows).isZero();
    verify(timedOutWorkflowNodeProgression, never())
        .advanceTimedOutWorkflowNode(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("CAS 成功对 PARTIAL_FAILED / TERMINATED / CANCELLED 等所有终态都触发 reconcile")
  void shouldReconcile_whenStatusIsAnyTerminal() {
    when(jobInstanceMapper.updateStatus(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.tenantId() != null
            && parameters.instanceStatus() != null)))
        .thenReturn(1);

    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.PARTIAL_FAILED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.TERMINATED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.CANCELLED.code()));

    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.TERMINATED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.CANCELLED.code());
  }
}
