package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 绑定候选所属租户上下文，再委派业务库事务执行 staging 代际查询和回收。 */
@Service
@RequiredArgsConstructor
public class ProcessStagingBusinessCleanupService {

  private final ProcessStagingBusinessCleanupTransaction transaction;

  public List<ProcessStagingGenerationCandidate> listExpired(
      String tenantId, int retentionHours, int batchSize, int offset) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.listExpired(tenantId, retentionHours, batchSize, offset));
  }

  public List<ProcessStagingGenerationCandidate> listExpiredAfter(
      String tenantId, int retentionHours, int batchSize, Instant createdAt, String generationId) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> transaction.listExpiredAfter(
            tenantId, retentionHours, batchSize, createdAt, generationId));
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
    String tenantId = candidate.getTenantId();
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.sealAndDelete(candidate, batchSize));
  }

  public ProcessStagingGenerationObservation observeExpiredRows(
      ProcessStagingGenerationCandidate candidate, int retentionHours) {
    String tenantId = candidate.getTenantId();
    return RlsTenantContextHolder.runWithTenant(
        tenantId, () -> transaction.observeExpiredRows(candidate, retentionHours));
  }
}
