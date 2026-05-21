package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.observability.JobLifecycleMetrics;
import com.example.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 守护 job_instance 终态 CAS + 子表收口的事务原子语义:
 *
 * <ul>
 *   <li>CAS 命中(rows > 0): 必须调 reconcileChildren(同事务保证子表跟进) + afterCommit 上报 metrics
 *   <li>CAS miss(rows == 0): 不能调 reconcileChildren,也不上报 metrics(避免污染)
 * </ul>
 */
class JobInstanceTerminalStatusApplicationServiceTest {

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobDefinitionMapper jobDefinitionMapper;
  @Mock private JobInstanceTerminalChildStateReconciler reconciler;
  @Mock private JobLifecycleMetrics jobLifecycleMetrics;

  private JobLifecycleMetricsRecorder recorder;
  private JobInstanceTerminalStatusApplicationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    recorder =
        new JobLifecycleMetricsRecorder(
            jobInstanceMapper, jobDefinitionMapper, jobLifecycleMetrics);
    service =
        new JobInstanceTerminalStatusApplicationService(jobInstanceMapper, reconciler, recorder);
  }

  private JobInstanceTerminalStatusCommand cmd(String terminal) {
    return new JobInstanceTerminalStatusCommand("ta", 100L, terminal, Instant.now(), 5L);
  }

  @Test
  @DisplayName("CAS 成功(rows=1) → 触发 reconcile,返 1")
  void reconcilesOnCasHit() {
    when(jobInstanceMapper.updateStatus(anyString(), anyLong(), anyString(), any(), anyLong()))
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
      verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.FAILED.code());
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
      verify(jobLifecycleMetrics)
          .recordCompletion(
              eq("ta"), eq("IMPORT"), eq(JobInstanceStatus.FAILED.code()), any(Duration.class));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("CAS miss(rows=0) → 不触发 reconcile,返 0(避免抹掉并发结果)")
  void skipsReconcileOnCasMiss() {
    when(jobInstanceMapper.updateStatus(anyString(), anyLong(), anyString(), any(), anyLong()))
        .thenReturn(0);

    int rows =
        service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.SUCCESS.code()));

    assertThat(rows).isZero();
    verify(reconciler, never()).reconcile(anyString(), anyLong(), anyString());
    verify(jobLifecycleMetrics, never())
        .recordCompletion(anyString(), anyString(), anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("CAS 成功对 PARTIAL_FAILED / TERMINATED / CANCELLED 等所有终态都触发 reconcile")
  void reconcilesForAllTerminalStatuses() {
    when(jobInstanceMapper.updateStatus(anyString(), anyLong(), anyString(), any(), anyLong()))
        .thenReturn(1);

    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.PARTIAL_FAILED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.TERMINATED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.CANCELLED.code()));

    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.TERMINATED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.CANCELLED.code());
  }
}
