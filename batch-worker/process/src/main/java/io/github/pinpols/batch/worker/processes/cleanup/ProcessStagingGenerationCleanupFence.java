package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 等此状态转换事务提交后再重放 DAG，避免子事务 */
@Service
@RequiredArgsConstructor
public class ProcessStagingGenerationCleanupFence {

  private final ProcessInvocationFenceMapper platformMapper;
  private final ProcessStagingBusinessCleanupService businessCleanup;

  @Transactional(transactionManager = "transactionManager")
  public int reclaimIfStale(ProcessStagingGenerationCandidate candidate, int batchSize) {
    if (!isStaleUnderFence(candidate)) {
      return 0;
    }

    // 等待当前请求事务释放实例锁，再确认 staging generation 仍可安全清理。
    // 与 TaskOutcomeDagProgressor 保持一致：终态结果必须先完成 DAG 推进，避免
    // 状态收敛与代际清理交错执行。
    return businessCleanup.sealAndDelete(candidate, batchSize);
  }

  @Transactional(transactionManager = "transactionManager")
  public ProcessStagingGenerationObservation observeIfStale(
      ProcessStagingGenerationCandidate candidate, int retentionHours) {
    if (!isStaleUnderFence(candidate)) {
      return new ProcessStagingGenerationObservation(0L, null);
    }
    return businessCleanup.observeExpiredRows(candidate, retentionHours);
  }

  private boolean isStaleUnderFence(ProcessStagingGenerationCandidate candidate) {
    Long instanceId =
        platformMapper.selectTaskInstanceId(candidate.getTenantId(), candidate.getTaskId());
    if (EmptyChecks.isNull(instanceId)) {
      return false;
    }
    platformMapper.acquireInstanceFence(candidate.getTenantId(), instanceId);

    // 触发的分支，避免运维人员跳过节点后条件/失败分支不可见。
    String currentGeneration = platformMapper.selectTaskCurrentStagingGeneration(
        candidate.getTenantId(), candidate.getTaskId());
    if (EmptyChecks.isNull(currentGeneration) || EmptyChecks.isBlank(currentGeneration)) {
      return false;
    }
    UUID current;
    try {
      current = UUID.fromString(currentGeneration);
    } catch (IllegalArgumentException invalidPlatformValue) {
      throw new IllegalStateException(
          "Invalid current PROCESS staging generation", invalidPlatformValue);
    }
    try {
      UUID candidateGeneration = UUID.fromString(candidate.getGenerationId());
      return !candidateGeneration.equals(current);
    } catch (IllegalArgumentException invalidCandidateValue) {
      throw new IllegalStateException(
          "Invalid candidate PROCESS staging generation", invalidCandidateValue);
    }
  }
}
