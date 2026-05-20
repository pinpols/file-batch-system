package com.example.batch.orchestrator.application.service.task;

import com.example.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.observability.JobLifecycleMetrics;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobInstanceTerminalChildStateReconciler terminalChildStateReconciler;
  private final JobLifecycleMetrics jobLifecycleMetrics;

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
      registerMetricsAfterCommit(command);
    }
    return rows;
  }

  /**
   * 事务提交后记 JobLifecycleMetrics:回滚不计数,失败也走 recordCompletion(status tag 区分); 再额外 selectById 拿
   * createdAt/jobDefinitionId 算 duration + 低基数 job_type —— 多 1 次 SELECT 是 metrics 显式成本,
   * 但只在终态切换发生(rows > 0)才命中,QPS 跟 job 完成速率绑死,可控。
   */
  private void registerMetricsAfterCommit(JobInstanceTerminalStatusCommand command) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              JobInstanceEntity instance =
                  jobInstanceMapper.selectById(command.tenantId(), command.id());
              if (instance == null || instance.getCreatedAt() == null) {
                return;
              }
              Instant finishedAt =
                  command.finishedAt() != null ? command.finishedAt() : Instant.now();
              Duration duration = Duration.between(instance.getCreatedAt(), finishedAt);
              jobLifecycleMetrics.recordCompletion(
                  command.tenantId(),
                  resolveJobType(instance),
                  command.terminalInstanceStatus(),
                  duration);
            } catch (RuntimeException ex) {
              log.warn(
                  "record job lifecycle metrics failed after commit: tenantId={} jobInstanceId={}",
                  command.tenantId(),
                  command.id(),
                  ex);
            }
          }
        });
  }

  private String resolveJobType(JobInstanceEntity instance) {
    if (instance.getJobDefinitionId() == null) {
      return "unknown";
    }
    JobDefinitionEntity definition = jobDefinitionMapper.selectById(instance.getJobDefinitionId());
    return definition == null || definition.jobType() == null || definition.jobType().isBlank()
        ? "unknown"
        : definition.jobType();
  }
}
