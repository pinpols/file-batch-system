package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 积压指标配置（{@code batch.metrics.backlog}）。
 *
 * <p>把原先 {@code BatchBacklogMetricsScheduler} 里的
 * {@code @Value("${batch.metrics.backlog.outbox-duplicate-window-hours:24}")} 收敛为类型安全配置，
 * 保留原有 key。
 */
@Data
@ConfigurationProperties(prefix = "batch.metrics.backlog")
public class BacklogMetricsProperties {

  /** outbox 重复投递检测窗口（小时）；实际按不小于 1 小时生效。 */
  private long outboxDuplicateWindowHours = 24;
}
