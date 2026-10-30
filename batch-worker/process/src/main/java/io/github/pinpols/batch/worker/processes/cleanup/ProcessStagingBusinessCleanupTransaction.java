package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.query.ProcessStagingExpiredScanQuery;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在 PROCESS 业务库事务中查询、封存和分批回收 staging 代际，并设置租户及代际 RLS 上下文。 */
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

  @Transactional(transactionManager = "processBusinessTransactionManager", readOnly = true)
  public List<ProcessStagingGenerationCandidate> listExpiredAfter(
      String tenantId, int retentionHours, int batchSize, Instant createdAt, String generationId) {
    RlsTenantSessionSupport.applyIfPresent(dataSource);
    return mapper.listExpiredGenerationsAfter(new ProcessStagingExpiredScanQuery(
        tenantId, retentionHours, batchSize, createdAt, generationId));
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
      if (EmptyChecks.isEmpty(Objects.requireNonNull(generations))) {
        return oldest;
      }
      for (ProcessStagingGenerationCandidate generation : generations) {
        mapper.setGenerationRlsContext(generation.getGenerationId());
        Instant generationOldest = mapper.selectOldestGenerationRow(generation);
        boolean generationHasEarlierStagedRow = EmptyChecks.isNotNull(generationOldest)
            && (EmptyChecks.isNull(oldest) || generationOldest.isBefore(oldest));
        if (generationHasEarlierStagedRow) {
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
    if (EmptyChecks.isNull(status) || "RECLAIMED".equals(status)) {
      return 0;
    }
    if ("OPEN".equals(status)) {
      int sealedRows = mapper.sealOpenGeneration(candidate);
      if (sealedRows != 1) {
        throw new IllegalStateException("PROCESS staging generation changed while locked");
      }
    } else if (!"SEALED".equals(status)) {
      throw new IllegalStateException("Unknown PROCESS staging generation status: " + status);
    }
    String generationId = candidate.getGenerationId().toString();
    mapper.setGenerationRlsContext(generationId);
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
    String generationId = candidate.getGenerationId().toString();
    mapper.setGenerationRlsContext(generationId);
    long count = mapper.countGenerationRowsOlderThan(candidate, retentionHours);
    Instant oldestRow = count == 0 ? null : mapper.selectOldestGenerationRow(candidate);
    return new ProcessStagingGenerationObservation(count, oldestRow);
  }
}
