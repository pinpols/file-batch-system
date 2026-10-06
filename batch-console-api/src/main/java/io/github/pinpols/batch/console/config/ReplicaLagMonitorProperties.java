package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Replica WAL replay lag 监控器调度配置（{@code batch.console.replica}）。
 *
 * <p>把原先 2 个同功能 {@code @Value} 收敛为类型安全配置，默认值与历史 {@code @Value} 默认值保持一致。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.replica")
public class ReplicaLagMonitorProperties {

  /** 采样间隔（毫秒）。 */
  private long lagMonitorIntervalMillis = 30_000L;

  /** 首次采样延迟（毫秒）。 */
  private long lagMonitorInitialDelayMillis = 10_000L;
}
