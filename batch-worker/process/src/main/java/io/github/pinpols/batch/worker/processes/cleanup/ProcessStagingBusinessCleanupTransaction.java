package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business-database transactions; RlsTenantContextHolder is set before each proxy entry. */
@Service
public class ProcessStagingBusinessCleanupTransaction {

  private final ProcessStagingMapper mapper;
  private final DataSource dataSource;

  public ProcessStagingBusinessCleanupTransaction(
      ProcessStagingMapper mapper, @Qualifier("processBusinessDataSource") DataSource dataSource) {
    this.mapper = mapper;
    this.dataSource = dataSource;
  }

  @Transactional(transactionManager = "processBusinessTransactionManager")
  public List<ProcessStagingGenerationCandidate> listExpired(
      String tenantId, int retentionHours, int batchSize, int offset) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    return mapper.listExpiredGenerations(tenantId, retentionHours, batchSize, offset);
  }

  @Transactional(transactionManager = "processBusinessTransactionManager")
  public long countExpiredGenerations(String tenantId, int retentionHours) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    return mapper.countExpiredGenerations(tenantId, retentionHours);
  }

  @Transactional(transactionManager = "processBusinessTransactionManager", readOnly = true)
  public Instant selectOldestStagedAt(String tenantId, int batchSize) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    Instant oldest = null;
    String cursor = null;
    while (true) {
      List<ProcessStagingGenerationCandidate> generations =
          mapper.listActiveGenerationsAfter(tenantId, cursor, batchSize);
      if (generations.isEmpty()) {
        return oldest;
      }
      for (ProcessStagingGenerationCandidate generation : generations) {
        mapper.setGenerationRlsContext(generation.getGenerationId());
        Instant generationOldest = mapper.selectOldestGenerationRow(generation);
        if (generationOldest != null && (oldest == null || generationOldest.isBefore(oldest))) {
          oldest = generationOldest;
        }
        cursor = generation.getGenerationId();
      }
      if (generations.size() < batchSize) {
        return oldest;
      }
    }
  }

  @Transactional(transactionManager = "processBusinessTransactionManager")
  public int sealAndDelete(ProcessStagingGenerationCandidate candidate, int batchSize) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    String status = mapper.selectGenerationStatusForUpdate(candidate);
    if (status == null || "RECLAIMED".equals(status)) {
      return 0;
    }
    if ("OPEN".equals(status)) {
      if (mapper.sealOpenGeneration(candidate) != 1) {
        throw new IllegalStateException("PROCESS staging generation changed while locked");
      }
    } else if (!"SEALED".equals(status)) {
      throw new IllegalStateException("Unknown PROCESS staging generation status: " + status);
    }
    mapper.setGenerationRlsContext(candidate.getGenerationId().toString());
    int deleted = mapper.deleteGenerationRows(candidate, batchSize);
    mapper.markGenerationReclaimedIfEmpty(candidate);
    return deleted;
  }

  @Transactional(transactionManager = "processBusinessTransactionManager")
  public ProcessStagingGenerationObservation observeExpiredRows(
      ProcessStagingGenerationCandidate candidate, int retentionHours) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    String status = mapper.selectGenerationStatusForUpdate(candidate);
    if (!"OPEN".equals(status)) {
      return new ProcessStagingGenerationObservation(0L, null);
    }
    mapper.setGenerationRlsContext(candidate.getGenerationId().toString());
    long count = mapper.countGenerationRowsOlderThan(candidate, retentionHours);
    return new ProcessStagingGenerationObservation(
        count, count == 0 ? null : mapper.selectOldestGenerationRow(candidate));
  }
}
