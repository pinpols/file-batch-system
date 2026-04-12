package com.example.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.drain")
public class WorkerDrainProperties {

  /** Orchestrator 接管飞行中任务前的默认排空等待窗口。 */
  private int defaultTimeoutSeconds = 600;

  /** 轮询处于 DRAINING 状态的 Worker 是否超期的间隔。 */
  private long checkIntervalMillis = 15000L;

  private boolean enabled = true;
}
