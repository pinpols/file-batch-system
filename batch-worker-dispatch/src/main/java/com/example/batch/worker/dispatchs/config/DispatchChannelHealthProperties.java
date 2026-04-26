package com.example.batch.worker.dispatchs.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分发渠道健康探针配置属性。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.health")
public class DispatchChannelHealthProperties {

  private boolean enabled = true;
  private long probeIntervalMillis = 60_000L;
  private long maxBackoffMillis = 15 * 60_000L;

  /**
   * A-3.9：半开模式下，CAS 抢到探针通行证后把 next_probe_at 推后的保留窗口（毫秒）。 需要大于典型 dispatch + report
   * 总耗时，防止探针结果未回写前被另一线程抢走； 小于 backoff 避免回写慢的极端路径锁死渠道。默认 30s。
   */
  private long halfOpenHoldMillis = 30_000L;

  private List<String> probeChannelTypes =
      new ArrayList<>(List.of("NAS", "OSS", "SFTP", "EMAIL", "API", "API_PUSH"));
}
