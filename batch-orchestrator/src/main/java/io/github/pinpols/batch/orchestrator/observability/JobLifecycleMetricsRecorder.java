package io.github.pinpols.batch.orchestrator.observability;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
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
 *       / 超时直接收敛
 *   <li>{@code DefaultTaskOutcomeService.applyTaskOutcome} — worker 上报终态
 * </ul>
 *
 * 设计约束:
 *
 * <ul>
 *   <li>必须在 {@code @Transactional} 内调用; 无同步上下文时静默 skip(不抛)。
 *   <li>worker report 热路径复用事务内已读取的实例快照，避免终态提交后再次查询同一实例；运维 / 超时
 *       路径没有现成快照，仍在 afterCommit 后读取一次，保证只为真正提交的终态记录指标。
 *   <li>整个 try/catch 回退; metrics 失败不影响业务事务也不抛。
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLifecycleMetricsRecorder {

  /** 失败类终态:这些状态要额外打 {@code batch.orchestrator.job.failure.total} + error_code。 */
  private static final Set<String> FAILED_TERMINAL_STATUSES = Set.of(
      JobInstanceStatus.FAILED.code(),
      JobInstanceStatus.PARTIAL_FAILED.code(),
      JobInstanceStatus.FAILED_DRY_RUN.code());

  private final JobInstanceMapper jobInstanceMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobLifecycleMetrics jobLifecycleMetrics;

  /**
   * 使用调用方事务内已经读取的实例记录终态指标。
   *
   * <p>传入实体不会跨事务捕获；本方法立即复制指标需要的字段为不可变快照。{@code failureClass}
   * 使用本次终态 CAS 即将写入的值，因为调用方持有的实体仍是更新前状态。
   */
  public void recordCompletionAfterCommit(
      String tenantId,
      JobInstanceEntity instance,
      String terminalStatus,
      Instant finishedAt,
      String failureClass) {
    if (EmptyChecks.isNull(instance) || EmptyChecks.isNull(instance.getCreatedAt())) {
      return;
    }
    CompletionSnapshot snapshot = new CompletionSnapshot(
        tenantId,
        instance.getId(),
        instance.getJobDefinitionId(),
        instance.getCreatedAt(),
        Boolean.TRUE.equals(instance.getDryRun()),
        failureClass);
    registerAfterCommit(snapshot, terminalStatus, finishedAt);
  }

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
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, jobInstanceId);
          if (instance == null || instance.getCreatedAt() == null) {
            return;
          }
          recordSnapshot(
              new CompletionSnapshot(
                  tenantId,
                  jobInstanceId,
                  instance.getJobDefinitionId(),
                  instance.getCreatedAt(),
                  Boolean.TRUE.equals(instance.getDryRun()),
                  instance.getFailureClass()),
              terminalStatus,
              finishedAt);
        } catch (RuntimeException ex) {
          logFailure(tenantId, jobInstanceId, ex);
        }
      }
    });
  }

  private void registerAfterCommit(
      CompletionSnapshot snapshot, String terminalStatus, Instant finishedAt) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          recordSnapshot(snapshot, terminalStatus, finishedAt);
        } catch (RuntimeException ex) {
          logFailure(snapshot.tenantId(), snapshot.jobInstanceId(), ex);
        }
      }
    });
  }

  private void recordSnapshot(
      CompletionSnapshot snapshot, String terminalStatus, Instant finishedAt) {
    Instant resolvedFinished =
        EmptyChecks.isNotNull(finishedAt) ? finishedAt : BatchDateTimeSupport.utcNow();
    Duration duration = Duration.between(snapshot.createdAt(), resolvedFinished);
    String jobType = resolveJobType(snapshot.jobDefinitionId());
    jobLifecycleMetrics.recordCompletion(
        snapshot.tenantId(), jobType, terminalStatus, snapshot.dryRun(), duration);
    // 失败指标必须使用终态 CAS 写入的 failure_class，不能从更新前的实体快照反推。
    if (FAILED_TERMINAL_STATUSES.contains(terminalStatus)) {
      jobLifecycleMetrics.recordFailure(
          snapshot.tenantId(),
          jobType,
          resolveErrorCode(snapshot.failureClass()),
          snapshot.dryRun());
    }
  }

  private void logFailure(String tenantId, Long jobInstanceId, RuntimeException ex) {
    log.warn(
        "record job lifecycle metrics failed after commit: tenantId={} jobInstanceId={}",
        tenantId,
        jobInstanceId,
        ex);
  }

  /**
   * 失败 error_code:优先 {@code failure_class}(ADR-012 故障分类),为空时回退 "unknown"。 JobInstance 表无显式
   * error_code 列(error_code 在 job_task 粒度),用 failure_class 作为汇总粒度的错误码。
   */
  private String resolveErrorCode(String failureClass) {
    return EmptyChecks.isBlank(failureClass) ? "unknown" : failureClass;
  }

  private String resolveJobType(Long jobDefinitionId) {
    if (EmptyChecks.isNull(jobDefinitionId)) {
      return "unknown";
    }
    JobDefinitionEntity definition = jobDefinitionMapper.selectById(jobDefinitionId);
    return definition == null
            || definition.jobType() == null
            || definition.jobType().isBlank()
        ? "unknown"
        : definition.jobType();
  }

  private record CompletionSnapshot(
      String tenantId,
      Long jobInstanceId,
      Long jobDefinitionId,
      Instant createdAt,
      boolean dryRun,
      String failureClass) {}
}
