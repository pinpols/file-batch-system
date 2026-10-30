package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerStartupAuditContributor;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PROCESS worker 启动时输出 staging 孤儿风险快照。 */
@Component
@RequiredArgsConstructor
@SuppressWarnings(
    "SpringJavaInjectionPointsAutowiringInspection") // Mapper 由应用启动类的业务库 @MapperScan 注册。
public class ProcessStagingStartupAuditContributor implements WorkerStartupAuditContributor {

  private final ProcessInvocationFenceMapper platformMapper;
  private final ProcessStagingBusinessCleanupService businessCleanup;
  private final ProcessStagingGenerationCleanupFence cleanupFence;
  private final ProcessStagingCleanupProperties properties;

  @Override
  public String name() {
    return "process-staging";
  }

  @Override
  public WorkerStartupAuditResult audit() {
    Map<String, Object> details = new LinkedHashMap<>();
    int retentionHours = Math.max(1, properties.getRetentionHours());
    int reportedBatchSize = Math.max(100, properties.getBatchSize());
    details.put("cleanupEnabled", properties.isEnabled());
    details.put("deletionEnabled", properties.isDeletionEnabled());
    details.put("retentionHours", retentionHours);
    details.put("batchSize", reportedBatchSize);
    int cleanupBatchSize = Math.max(100, properties.getBatchSize());
    long orphanRows = 0;
    long candidateGenerations = 0;
    long scannedGenerations = 0;
    long oldestAgeSeconds = 0;
    boolean scanTruncated = false;
    for (String tenantId : platformMapper.listCleanupTenants()) {
      long tenantCandidates = businessCleanup.countExpiredGenerations(tenantId, retentionHours);
      candidateGenerations += tenantCandidates;
      List<ProcessStagingGenerationCandidate> candidates =
          businessCleanup.listExpired(tenantId, retentionHours, cleanupBatchSize, 0);
      scanTruncated |= tenantCandidates > candidates.size();
      for (ProcessStagingGenerationCandidate candidate : candidates) {
        ProcessStagingGenerationObservation observation =
            cleanupFence.observeIfStale(candidate, retentionHours);
        orphanRows += observation.overdueRows();
        scannedGenerations++;
        if (EmptyChecks.isNotNull(observation.oldestRowAt())) {
          long currentEpochMillis = BatchDateTimeSupport.utcEpochMillis();
          long oldestRowEpochMillis = observation.oldestRowAt().toEpochMilli();
          long ageSeconds = Math.max(0, (currentEpochMillis - oldestRowEpochMillis) / 1000);
          oldestAgeSeconds = Math.max(oldestAgeSeconds, ageSeconds);
        }
      }
    }
    details.put("candidateGenerationsPastRetention", candidateGenerations);
    details.put("scannedGenerations", scannedGenerations);
    details.put("generationScanTruncated", scanTruncated);
    details.put("oldestAgeSeconds", oldestAgeSeconds);
    details.put("orphanRowsPastRetention", orphanRows);
    boolean healthy = (orphanRows == 0 && !scanTruncated)
        || (properties.isEnabled() && properties.isDeletionEnabled());
    return healthy
        ? WorkerStartupAuditResult.healthy(details)
        : WorkerStartupAuditResult.unhealthy(details);
  }
}
