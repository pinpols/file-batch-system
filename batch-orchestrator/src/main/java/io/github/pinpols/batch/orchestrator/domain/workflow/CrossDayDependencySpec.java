package io.github.pinpols.batch.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * ADR-018 跨批量日依赖单元声明（{@code workflow_node.cross_day_dependencies} JSONB 数组单元素）。
 *
 * <p>解析规则：
 *
 * <ul>
 *   <li>{@link #bizDateOffset} 与 {@link #bizDateRange} 互斥：offset 单点（T-N），range 多点（PREV_5_BIZ_DAYS /
 *       MTD / LAST_4_WEEKS）；
 *   <li>{@link #scope}：REQUIRED → 缺则等；OPTIONAL → 缺则跳过引用，节点照常启动；
 *   <li>{@link #consumeVersionStrategy}：EFFECTIVE_ONLY（默认）/ LATEST_INCLUDING_PENDING /
 *       SPECIFIC_VERSION；
 *   <li>{@link #specificVersionNo}：仅 SPECIFIC_VERSION 时使用，其他策略下忽略。
 * </ul>
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrossDayDependencySpec(
    String alias,
    String jobCode,
    Integer bizDateOffset,
    String bizDateRange,
    String scope,
    String consumeVersionStrategy,
    Integer specificVersionNo) {

  /** REQUIRED 是默认 scope。 */
  public static final String SCOPE_REQUIRED = "REQUIRED";

  public static final String SCOPE_OPTIONAL = "OPTIONAL";

  public static final String STRATEGY_EFFECTIVE_ONLY = "EFFECTIVE_ONLY";
  public static final String STRATEGY_LATEST_INCLUDING_PENDING = "LATEST_INCLUDING_PENDING";
  public static final String STRATEGY_SPECIFIC_VERSION = "SPECIFIC_VERSION";

  /** REQUIRED 是默认 scope（未声明等价 REQUIRED，强一致性）。 */
  public boolean isRequired() {
    return scope == null || SCOPE_REQUIRED.equalsIgnoreCase(scope);
  }

  /** EFFECTIVE_ONLY 是默认策略（未声明等价 EFFECTIVE_ONLY）。 */
  public String resolvedStrategy() {
    return consumeVersionStrategy == null || consumeVersionStrategy.isBlank()
        ? STRATEGY_EFFECTIVE_ONLY
        : consumeVersionStrategy;
  }

  public boolean isOffsetMode() {
    return bizDateOffset != null;
  }

  public boolean isRangeMode() {
    return bizDateRange != null && !bizDateRange.isBlank();
  }
}
