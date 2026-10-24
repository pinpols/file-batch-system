package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Holds the Orchestrator instance fence while the separate business transaction reclaims stale data. */
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

    // Old generations are unreachable by checkpoint resume: resume reads the task's current
    // generation.
    // The business service seals the exact ledger identity and deletes its rows in one business
    // transaction.
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
    if (instanceId == null) {
      return false;
    }
    platformMapper.acquireInstanceFence(candidate.getTenantId(), instanceId);

    // Re-read after acquiring the same advisory fence used by stage execution, retries and reclaim.
    String currentGeneration = platformMapper.selectTaskCurrentStagingGeneration(
        candidate.getTenantId(), candidate.getTaskId());
    if (currentGeneration == null || currentGeneration.isBlank()) {
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
      return !UUID.fromString(candidate.getGenerationId()).equals(current);
    } catch (IllegalArgumentException invalidCandidateValue) {
      throw new IllegalStateException(
          "Invalid candidate PROCESS staging generation", invalidCandidateValue);
    }
  }
}
