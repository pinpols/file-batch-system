package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.core.infrastructure.WorkerStartupAuditContributor;
import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** DISPATCH worker 启动时输出渠道健康门控快照。 */
@Component
@RequiredArgsConstructor
public class DispatchChannelStartupAuditContributor implements WorkerStartupAuditContributor {

  private final DispatchChannelHealthRepository repository;
  private final DispatchChannelHealthProperties properties;

  @Override
  public String name() {
    return "dispatch-channel-health";
  }

  @Override
  public WorkerStartupAuditResult audit() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("enabled", properties.isEnabled());
    details.put("probeIntervalMillis", properties.getProbeIntervalMillis());
    details.put("maxBackoffMillis", properties.getMaxBackoffMillis());
    details.put("probeChannelTypes", properties.getProbeChannelTypes());
    if (!properties.isEnabled()) {
      return WorkerStartupAuditResult.healthy(details);
    }
    long degraded = repository.countByHealthStatus("DEGRADED");
    long unhealthy = repository.countByHealthStatus("UNHEALTHY");
    long overdue = repository.countProbeOverdue(Instant.now());
    details.put("degradedChannels", degraded);
    details.put("unhealthyChannels", unhealthy);
    details.put("probeOverdueChannels", overdue);
    boolean healthy = unhealthy == 0;
    return healthy
        ? WorkerStartupAuditResult.healthy(details)
        : WorkerStartupAuditResult.unhealthy(details);
  }
}
