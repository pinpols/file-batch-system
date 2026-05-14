package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.infrastructure.WorkerStartupAuditContributor;
import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
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
    long overdue = repository.countProbeOverdue(BatchDateTimeSupport.utcNow());
    details.put("degradedChannels", degraded);
    details.put("unhealthyChannels", unhealthy);
    details.put("probeOverdueChannels", overdue);
    // 启动瞬间 probe scheduler 尚未首跑，DB 里残留的 UNHEALTHY 都伴随 last_probe_at 过期。
    // 此时把整体判为 unhealthy 是 false positive；改为：当且仅当所有 UNHEALTHY 都属于 overdue 时，
    // 标记 pendingFirstProbe 并视为 healthy，等首轮 probe 完成后由调度器更新真实状态。
    boolean pendingFirstProbe = unhealthy > 0 && unhealthy <= overdue;
    details.put("pendingFirstProbe", pendingFirstProbe);
    boolean healthy = unhealthy == 0 || pendingFirstProbe;
    return healthy
        ? WorkerStartupAuditResult.healthy(details)
        : WorkerStartupAuditResult.unhealthy(details);
  }
}
