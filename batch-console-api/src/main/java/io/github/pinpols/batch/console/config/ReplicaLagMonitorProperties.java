package io.github.pinpols.batch.console.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Replica WAL replay lag 监控器调度配置（{@code batch.console.replica}）。
 *
 * <p>把原先 2 个同功能 {@code @Value} 收敛为类型安全配置，默认值与历史 {@code @Value} 默认值保持一致。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.console.replica")
public class ReplicaLagMonitorProperties {

  /** 采样间隔（毫秒）。 */
  @Min(value = 1, message = "replica lag monitor interval must be positive")
  private long lagMonitorIntervalMillis = 30_000L;

  /** 首次采样延迟（毫秒）。 */
  @Min(value = 0, message = "replica lag monitor initial delay must not be negative")
  private long lagMonitorInitialDelayMillis = 10_000L;
}
