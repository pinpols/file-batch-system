package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 job_instance 终态 CAS + 子表收口的事务原子语义:
 *
 * <ul>
 *   <li>CAS 命中(rows > 0): 必须调 reconcileChildren(同事务保证子表跟进)
 *   <li>CAS miss(rows == 0): 不能调 reconcileChildren,避免抹掉别的并发写入的结果
 * </ul>
 */
class JobInstanceTerminalStatusApplicationServiceTest {

  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobInstanceTerminalChildStateReconciler reconciler;

  private JobInstanceTerminalStatusApplicationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new JobInstanceTerminalStatusApplicationService(jobInstanceMapper, reconciler);
  }

  private JobInstanceTerminalStatusCommand cmd(String terminal) {
    return new JobInstanceTerminalStatusCommand("ta", 100L, terminal, Instant.now(), 5L);
  }

  @Test
  @DisplayName("CAS 成功(rows=1) → 触发 reconcile,返 1")
  void reconciles_on_cas_hit() {
    when(jobInstanceMapper.updateStatus(
            anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyLong()))
        .thenReturn(1);

    int rows =
        service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.FAILED.code()));

    assertThat(rows).isEqualTo(1);
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.FAILED.code());
  }

  @Test
  @DisplayName("CAS miss(rows=0) → 不触发 reconcile,返 0(避免抹掉并发结果)")
  void skips_reconcile_on_cas_miss() {
    when(jobInstanceMapper.updateStatus(
            anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyLong()))
        .thenReturn(0);

    int rows =
        service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.SUCCESS.code()));

    assertThat(rows).isZero();
    verify(reconciler, never()).reconcile(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("CAS 成功对 PARTIAL_FAILED / TERMINATED / CANCELLED 等所有终态都触发 reconcile")
  void reconciles_for_all_terminal_statuses() {
    when(jobInstanceMapper.updateStatus(
            anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), anyLong()))
        .thenReturn(1);

    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.PARTIAL_FAILED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.TERMINATED.code()));
    service.updateTerminalStatusAndReconcileChildren(cmd(JobInstanceStatus.CANCELLED.code()));

    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.TERMINATED.code());
    verify(reconciler).reconcile("ta", 100L, JobInstanceStatus.CANCELLED.code());
  }
}
