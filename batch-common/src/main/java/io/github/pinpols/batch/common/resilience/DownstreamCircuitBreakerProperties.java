package io.github.pinpols.batch.common.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link DownstreamFallback} 内部 resilience4j CircuitBreaker 的配置。
 *
 * <p>每个 {@code service} 标签对应一个 per-service CircuitBreaker 实例(如 {@code trigger} / {@code
 * orchestrator});默认值取 spike 文档(docs/spike/resilience4j-sb4-compat.md)建议基线。{@link #enabled} 为
 * kill-switch:关闭后 {@link DownstreamFallback} 退化为纯 try/catch 降级模板(不经熔断状态机),对外契约不变。
 */
@Data
@ConfigurationProperties(prefix = "batch.resilience.downstream")
public class DownstreamCircuitBreakerProperties {

  /** 总开关。关闭时退回旧的 try/catch 降级(无熔断状态机),用于紧急回退。 */
  private boolean enabled = true;

  /** 失败率阈值(百分比),窗口内失败率达到即 CLOSED→OPEN。 */
  private float failureRateThreshold = 50.0f;

  /** COUNT_BASED 滑动窗口大小(最近 N 次调用)。 */
  private int slidingWindowSize = 20;

  /** 触发失败率计算所需的最小调用数(未达则不熔断,避免冷启动误判)。 */
  private int minimumNumberOfCalls = 10;

  /** OPEN 停留时长(毫秒,默认 30s),到期后下一次调用转入 HALF_OPEN 试探。 */
  private long waitDurationInOpenStateMillis = 30_000L;

  /** HALF_OPEN 允许的受限试探调用数。 */
  private int permittedCallsInHalfOpen = 5;
}
