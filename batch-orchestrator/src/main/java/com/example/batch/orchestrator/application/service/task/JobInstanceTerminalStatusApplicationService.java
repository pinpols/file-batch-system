package com.example.batch.orchestrator.application.service.task;

import com.example.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * job_instance 写入业务终态时与 {@link JobInstanceTerminalChildStateReconciler}
 * 必须在<strong>同一事务</strong>内完成。
 *
 * <p>若仅 {@link JobInstanceMapper#updateStatus} 单独提交而收口子表失败或进程中断，会出现「实例终态但分区/任务仍活跃」的运行态不一致，
 * 节流与审计计数会被污染。{@link DefaultTaskOutcomeService#applyTaskOutcome}
 * 整体已有事务包裹；本服务覆盖<strong>直接</strong>调用 {@code updateStatus} 的运维与超时路径。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobInstanceTerminalStatusApplicationService {

  private final JobInstanceMapper jobInstanceMapper;
  private final JobInstanceTerminalChildStateReconciler terminalChildStateReconciler;
  private final JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder;

  /** CAS 更新实例终态并收口非终态分区/任务；返回受影响行数（0 表示并发抢占或未命中）。 */
  @Transactional
  public int updateTerminalStatusAndReconcileChildren(JobInstanceTerminalStatusCommand command) {
    int rows =
        jobInstanceMapper.updateStatus(
            command.tenantId(),
            command.id(),
            command.terminalInstanceStatus(),
            command.finishedAt(),
            command.expectedVersion());
    if (rows > 0) {
      terminalChildStateReconciler.reconcile(
          command.tenantId(), command.id(), command.terminalInstanceStatus());
      jobLifecycleMetricsRecorder.recordCompletionAfterCommit(
          command.tenantId(), command.id(), command.terminalInstanceStatus(), command.finishedAt());
    }
    return rows;
  }
}
