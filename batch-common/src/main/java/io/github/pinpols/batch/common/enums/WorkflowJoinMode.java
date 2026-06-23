package io.github.pinpols.batch.common.enums;

import io.github.pinpols.batch.common.exception.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowJoinMode implements DictEnum {
  ALL("ALL", "全部满足后汇聚"),
  ANY("ANY", "任一满足即汇聚"),
  N_OF("N_OF", "满足指定数量后汇聚");

  private final String code;
  private final String label;

  /** 空白回落到 ALL；未知 code 抛异常，避免配置错误被静默捕获并抑制（L-3）。 */
  public static WorkflowJoinMode fromCode(String code) {
    if (code == null || code.isBlank()) {
      return ALL;
    }
    WorkflowJoinMode match = DictEnum.fromCode(WorkflowJoinMode.class, code);
    if (match == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.enum.unknown_workflow_join_mode_code", code);
    }
    return match;
  }

  /** 安全变体：未知 code 时返回 ALL，用于读取可能含历史废弃模式的旧数据。 */
  public static WorkflowJoinMode fromCodeOrDefault(String code) {
    WorkflowJoinMode match = DictEnum.fromCode(WorkflowJoinMode.class, code);
    return match != null ? match : ALL;
  }
}
