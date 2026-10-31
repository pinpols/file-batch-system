package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持有平台库实例围栏并确认候选已被当前代际替换后，调用业务库执行观测或回收。 */
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

    // 持有实例围栏直到业务库操作结束，避免当前代际在核对后被并发切换。
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

    // 围栏内重新读取任务的当前代际；不能确认当前代际时不回收。
    String currentGeneration = platformMapper.selectTaskCurrentStagingGeneration(
        candidate.getTenantId(), candidate.getTaskId());
    if (EmptyChecks.isBlank(currentGeneration)) {
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
