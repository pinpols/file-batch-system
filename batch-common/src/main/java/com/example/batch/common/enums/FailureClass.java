package com.example.batch.common.enums;

import com.example.batch.common.exception.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ADR-012 失败分类一等公民。
 *
 * <p>同一 `FAILED` 终态下, 7 类失败的响应完全不同:
 *
 * <ul>
 *   <li>{@link #INFRASTRUCTURE} — DB / Kafka / 网络抖动: 自动重试候选
 *   <li>{@link #DATA_QUALITY} — 数据质量异常: 业务方介入, 不重试
 *   <li>{@link #BUSINESS_RULE} — 业务规则不通过: 跳过该笔, 不算 job 级失败
 *   <li>{@link #CONFIG} — 配置错: ops 修配置后人工 RERUN
 *   <li>{@link #UPSTREAM_DELAY} — 前置 job / 文件未就绪: 走 ADR-018 等待
 *   <li>{@link #TIMEOUT} — 超时, 上下文相关; v1 默认 UNKNOWN, 二次分类是 v2
 *   <li>{@link #UNKNOWN} — classifier / worker 都没把握, 触发 ops review
 * </ul>
 *
 * <p>分类来源（写入侧由近及远）: worker {@code TaskExecutionReportDto.failureClass} → orchestrator 端 {@link
 * com.example.batch.common.exception.BizException#getFailureClass()} → `FailureClassifier` chain
 * 兜底。 永远不要为了显得"有分类"瞎猜; UNKNOWN 是合法终态值。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FailureClass implements DictEnum {
  INFRASTRUCTURE("INFRASTRUCTURE", "基础设施异常 — 自动重试候选"),
  DATA_QUALITY("DATA_QUALITY", "数据质量异常 — 业务方介入"),
  BUSINESS_RULE("BUSINESS_RULE", "业务规则异常 — 跳过或人工裁决"),
  CONFIG("CONFIG", "配置异常 — ops 修配置后 RERUN"),
  UPSTREAM_DELAY("UPSTREAM_DELAY", "上游延迟 — 等待 / WAITING_DEPENDENCY"),
  TIMEOUT("TIMEOUT", "超时 — 上下文相关"),
  UNKNOWN("UNKNOWN", "未分类 — 需要 ops 人工判定");

  private final String code;
  private final String label;

  /** 空白回落 UNKNOWN; 未知 code 抛异常, 避免静默吞掉。 */
  public static FailureClass fromCode(String code) {
    if (code == null || code.isBlank()) {
      return UNKNOWN;
    }
    FailureClass match = DictEnum.fromCode(FailureClass.class, code);
    if (match == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.enum.unknown_failure_class_code", code);
    }
    return match;
  }

  /** 安全变体: 空白 / 未知 code 都返回 UNKNOWN（worker 上报路径用，避免反序列化抛异常）。 */
  public static FailureClass fromCodeOrUnknown(String code) {
    FailureClass match = DictEnum.fromCode(FailureClass.class, code);
    return match != null ? match : UNKNOWN;
  }
}
