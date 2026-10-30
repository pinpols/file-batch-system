package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ADR-028 Sensor 超时后的下游处理策略。
 *
 * <ul>
 *   <li>FAIL — 推进节点到 FAILED，整条 workflow 走失败分支
 *   <li>SKIP_DOWNSTREAM — 推进到 SKIPPED，并级联跳过因此不可达的下游节点；其它可达分支仍按 DAG/JOIN 规则运行，Workflow 无失败时可成功收口
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
