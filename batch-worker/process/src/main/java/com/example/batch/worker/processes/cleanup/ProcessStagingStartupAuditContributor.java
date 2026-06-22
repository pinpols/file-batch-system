package com.example.batch.worker.processes.cleanup;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.infrastructure.WorkerStartupAuditContributor;
import com.example.batch.worker.processes.mapper.business.ProcessStagingMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PROCESS worker 启动时输出 staging 孤儿风险快照。 */
@Component
@RequiredArgsConstructor
public class ProcessStagingStartupAuditContributor implements WorkerStartupAuditContributor {

  private final ProcessStagingMapper processStagingMapper;
  private final ProcessStagingCleanupProperties properties;

  @Override
  public String name() {
    return "process-staging";
  }

  @Override
  public WorkerStartupAuditResult audit() {
    Map<String, Object> details = new LinkedHashMap<>();
    int retentionHours = Math.max(1, properties.getRetentionHours());
    details.put("cleanupEnabled", properties.isEnabled());
    details.put("retentionHours", retentionHours);
    details.put("batchSize", Math.max(100, properties.getBatchSize()));
    Instant oldest = processStagingMapper.selectMinStagedAt();
    long oldestAgeSeconds =
        oldest == null
            ? 0
            : Math.max(0, (BatchDateTimeSupport.utcEpochMillis() - oldest.toEpochMilli()) / 1000);
    long orphanRows = processStagingMapper.countOrphansOlderThan(retentionHours);
    details.put("oldestAgeSeconds", oldestAgeSeconds);
    details.put("orphanRowsPastRetention", orphanRows);
    boolean healthy = properties.isEnabled() || orphanRows == 0;
    return healthy
        ? WorkerStartupAuditResult.healthy(details)
        : WorkerStartupAuditResult.unhealthy(details);
  }
}
