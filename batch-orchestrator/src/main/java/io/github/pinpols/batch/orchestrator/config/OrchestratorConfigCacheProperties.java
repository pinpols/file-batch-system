package io.github.pinpols.batch.orchestrator.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Orchestrator 配置近缓存参数。 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.orchestrator.config-cache")
public class OrchestratorConfigCacheProperties {

  /**
   * 作业、工作流定义及资源队列的进程内缓存 TTL(ms)。默认 250ms，仅合并同一 launch 突发内的重复读取；
   * 资源队列的空结果也按该 TTL 缓存，跨进程配置变更最迟在该窗口结束后可见。
   */
  @Min(
      value = 1,
      message = "batch.orchestrator.config-cache.local-positive-ttl-millis must be at least 1")
  private long localPositiveTtlMillis = 250L;

  /** 进程内配置缓存最大条目数，作业定义、工作流定义、租户配额策略和租户资源队列各自独立计数。 */
  @Min(
      value = 1,
      message = "batch.orchestrator.config-cache.local-positive-maximum-size must be at least 1")
  private long localPositiveMaximumSize = 10_000L;
}
