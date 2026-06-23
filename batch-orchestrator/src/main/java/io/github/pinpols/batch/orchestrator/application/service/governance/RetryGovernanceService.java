package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;

public interface RetryGovernanceService {

  boolean scheduleRetryIfNecessary(
      JobTaskEntity task,
      JobPartitionEntity partition,
      JobInstanceEntity jobInstance,
      String errorCode,
      String errorMessage);

  void dispatchDueRetries();

  void retryPartition(String tenantId, Long partitionId, String eventKey);

  /**
   * 重新派发任务进行重试。当任务关联了分区时，委托给 {@link #retryPartition}； 当 {@code job_partition_id} 为 null
   * 时，重置任务并写入不带分区行的派发 outbox。
   */
  void retryTask(String tenantId, Long taskId, String eventKey);

  /** 强制回收可能处于 RUNNING 状态的任务（例如 Worker 排空接管期间）。 与 {@link #retryTask} 不同，此方法除终态外还接受 RUNNING 状态。 */
  void reclaimTask(String tenantId, Long taskId, String eventKey);

  void replayDeadLetter(String tenantId, Long deadLetterTaskId);

  /**
   * 人工运维触发的死信重放,带 audit(P1-1 修复)。 operatorId / reason 写入 job_execution_log,便于事后追溯"谁在何时为何重放了哪条死信"。
   * idempotencyKey 留作未来 dedupe 占位(当前 markReplaying CAS 已具备幂等保护,本字段会写入 audit 但暂不参与去重)。
   */
  default void replayDeadLetter(
      String tenantId,
      Long deadLetterTaskId,
      String operatorId,
      String reason,
      String idempotencyKey) {
    // 默认实现退化到不带 audit 的路径,具体实现类应覆盖以追加 audit。
    replayDeadLetter(tenantId, deadLetterTaskId);
  }

  /**
   * V90: 扫描到期的 SYSTEM-class 死信记录，自动触发 {@link #replayDeadLetter}。 业务错误（{@code BUSINESS}）不会被这里 pick
   * up；超过 {@code max_replay_count} 的记录由本方法转 GIVE_UP。
   */
  void autoRetryDueDeadLetters();
}
