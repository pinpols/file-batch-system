package io.github.pinpols.batch.trigger.infrastructure.readiness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游就绪门禁开关（{@code batch.trigger.readiness-gate}，ADR-043 依赖感知 fire）。
 *
 * <p>把原先 {@code UpstreamReadinessChecker} 构造器里的
 * {@code @Value("${batch.trigger.readiness-gate.enabled:true}")} 收敛为类型安全配置，保留原有 key。
 * 紧急开关关闭时跳过上游查询、一律放行 fire。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.readiness-gate")
public class ReadinessGateProperties {

  /** 是否启用上游就绪门禁；false 时跳过查询、一律放行。 */
  private boolean enabled = true;
}
