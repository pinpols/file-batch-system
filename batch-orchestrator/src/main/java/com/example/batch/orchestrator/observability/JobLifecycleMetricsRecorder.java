package com.example.batch.orchestrator.observability;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobDefinitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 统一封装 {@link JobLifecycleMetrics#recordCompletion} 的 afterCommit 调度, 让 job_instance
 * 走到终态的两条写路径共用一套实现。
 *
 * <p>覆盖路径:
 *
 * <ul>
 *   <li>{@code
 *       JobInstanceTerminalStatusApplicationService.updateTerminalStatusAndReconcileChildren} — 运维
 *       / 超时直接收口
 *   <li>{@code DefaultTaskOutcomeService.applyTaskOutcome} — worker 上报终态
 * </ul>
 *
 * 设计约束:
 *
 * <ul>
 *   <li>必须在 {@code @Transactional} 内调用; 无同步上下文时静默 skip(不抛)。
 *   <li>afterCommit 路径再 selectById 拿 createdAt + jobDefinitionId; 多 1 次 SELECT 是 metrics 显式成本,
 *       仅终态切换发生时命中。
 *   <li>整个 try/catch 兜底; metrics 失败不影响业务事务也不抛。
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLifecycleMetricsRecorder {

  /** 失败类终态:这些状态要额外打 {@code batch.orchestrator.job.failure.total} + error_code。 */
  private static final Set<String> FAILED_TERMINAL_STATUSES =
      Set.of(
          JobInstanceStatus.FAILED.code(),
          JobInstanceStatus.PARTIAL_FAILED.code(),
          JobInstanceStatus.FAILED_DRY_RUN.code());

  private final JobInstanceMapper jobInstanceMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobLifecycleMetrics jobLifecycleMetrics;

  /**
   * 在事务提交后记一笔 completion(duration + counter)。回滚 / 同步不可用时 no-op。
   *
   * @param tenantId 必填
   * @param jobInstanceId 必填; 用于 afterCommit 阶段重新拉实例算 duration
   * @param terminalStatus SUCCESS / FAILED / CANCELLED / PARTIAL_FAILED / TERMINATED
   * @param finishedAt 若 null 则取 afterCommit 时刻
   */
  public void recordCompletionAfterCommit(
      String tenantId, Long jobInstanceId, String terminalStatus, Instant finishedAt) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, jobInstanceId);
              if (instance == null || instance.getCreatedAt() == null) {
                return;
              }
              Instant resolvedFinished =
                  finishedAt != null ? finishedAt : BatchDateTimeSupport.utcNow();
              Duration duration = Duration.between(instance.getCreatedAt(), resolvedFinished);
              // ADR-026:dry_run 维度切分指标 — Boolean 字段可能为 null,缺省按 false(非演练)处理
              boolean dryRun = Boolean.TRUE.equals(instance.getDryRun());
              String jobType = resolveJobType(instance);
              jobLifecycleMetrics.recordCompletion(
                  tenantId, jobType, terminalStatus, dryRun, duration);
              // P0 (review 2026-05-21): 失败类终态补打 JOB_FAILURE_TOTAL + error_code,
              // 否则 batch.orchestrator.job.failure.total 永久为 0,error_code 分桶报警失效。
              if (FAILED_TERMINAL_STATUSES.contains(terminalStatus)) {
                jobLifecycleMetrics.recordFailure(
                    tenantId, jobType, resolveErrorCode(instance), dryRun);
              }
            } catch (RuntimeException ex) {
              log.warn(
                  "record job lifecycle metrics failed after commit:"
                      + " tenantId={} jobInstanceId={}",
                  tenantId,
                  jobInstanceId,
                  ex);
            }
          }
        });
  }

  /**
   * 失败 error_code:优先 {@code failure_class}(ADR-012 故障分类),为空时回退 "unknown"。 JobInstance 表无显式
   * error_code 列(error_code 在 job_task 粒度),用 failure_class 作为汇总粒度的错误码。
   */
  private String resolveErrorCode(JobInstanceEntity instance) {
    String fc = instance.getFailureClass();
    return fc == null || fc.isBlank() ? "unknown" : fc;
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
