package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

/**
 * ADR-023 §决策 §Calendar Dependency — 跨 calendar 串联依赖。
 *
 * <p>{@code rule}：WAIT_SETTLED / WAIT_CUTOFF / SAME_DAY_PARALLEL（v1 仅 WAIT_SETTLED 完整支持）。
 */
@Builder(toBuilder = true)
public record CalendarDependencyEntity(
    Long id,
    String tenantId,
    String upstreamCode,
    String downstreamCode,
    String rule,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public static final String RULE_WAIT_SETTLED = "WAIT_SETTLED";
  public static final String RULE_WAIT_CUTOFF = "WAIT_CUTOFF";
  public static final String RULE_SAME_DAY_PARALLEL = "SAME_DAY_PARALLEL";
}
