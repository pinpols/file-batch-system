package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ADR-028 Sensor 超时后的下游处理策略。
 *
 * <ul>
 *   <li>FAIL — 推进节点到 FAILED，整条 workflow 走失败分支
 *   <li>SKIP_DOWNSTREAM — 推进到 SKIPPED，下游节点不执行但 workflow 继续往后走（用于"可选信号"场景）
 * </ul>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum SensorTimeoutAction implements DictEnum {
  FAIL("FAIL", "失败"),
  SKIP_DOWNSTREAM("SKIP_DOWNSTREAM", "跳过下游");

  private final String code;
  private final String label;
}
