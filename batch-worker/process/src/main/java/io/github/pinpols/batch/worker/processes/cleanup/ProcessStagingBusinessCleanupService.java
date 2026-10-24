package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Routes to the tenant's business shard before starting a transactional cleanup operation. */
@Service
@RequiredArgsConstructor
public class ProcessStagingBusinessCleanupService {

  private final ProcessStagingBusinessCleanupTransaction transaction;

  public List<ProcessStagingGenerationCandidate> listExpired(
      String tenantId, int retentionHours, int batchSize, int offset) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.listExpired(tenantId, retentionHours, batchSize, offset));
  }

  public long countExpiredGenerations(String tenantId, int retentionHours) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.countExpiredGenerations(tenantId, retentionHours));
  }

  public Instant selectOldestStagedAt(String tenantId, int batchSize) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.selectOldestStagedAt(tenantId, batchSize));
  }

  public int sealAndDelete(ProcessStagingGenerationCandidate candidate, int batchSize) {
    return RlsTenantContextHolder.runWithTenant(
        candidate.getTenantId(), () -> transaction.sealAndDelete(candidate, batchSize));
  }

  public ProcessStagingGenerationObservation observeExpiredRows(
      ProcessStagingGenerationCandidate candidate, int retentionHours) {
    return RlsTenantContextHolder.runWithTenant(
        candidate.getTenantId(), () -> transaction.observeExpiredRows(candidate, retentionHours));
  }
}
