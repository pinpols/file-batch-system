package com.example.batch.common.spi.task;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * {@link BatchTaskExecutor} 的能力声明 — 资源占用倾向 + 行为特性。
 *
 * <p>orchestrator 用本结构做调度策略(优先级 / 配额 / cancellable check),worker 用本结构上报路由元信息。
 *
 * @param resourceKinds 资源占用集合(可多选,如 SQL = {DB},SFTP = {NET, DISK})
 * @param idempotent 重跑是否安全(true → 失败可直接重试;false → 需补偿)
 * @param cancellable 是否支持 {@link BatchTaskExecutor#cancel(String)}(必须跟实际实现一致)
 * @param recommendedTimeout 建议超时,用户未配置时的兜底值
 */
public record TaskCapability(
    Set<ResourceKind> resourceKinds,
    boolean idempotent,
    boolean cancellable,
    Duration recommendedTimeout) {

  public TaskCapability {
    Objects.requireNonNull(resourceKinds, "resourceKinds");
    Objects.requireNonNull(recommendedTimeout, "recommendedTimeout");
    if (resourceKinds.isEmpty()) {
      throw new IllegalArgumentException("resourceKinds must not be empty");
    }
    if (recommendedTimeout.isNegative() || recommendedTimeout.isZero()) {
      throw new IllegalArgumentException("recommendedTimeout must be positive");
    }
    resourceKinds = Set.copyOf(resourceKinds);
  }

  /** 便捷构造:idempotent=true, cancellable=true, recommendedTimeout=5min。 */
  public static TaskCapability of(ResourceKind... kinds) {
    return new TaskCapability(Set.of(kinds), true, true, Duration.ofMinutes(5));
  }
}
