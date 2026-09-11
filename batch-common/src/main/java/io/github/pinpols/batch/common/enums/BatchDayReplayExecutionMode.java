package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 批量日重放会话的执行模式。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchDayReplayExecutionMode implements DictEnum {
  REPLAY("REPLAY", "正式重放"),
  DRY_RUN("DRY_RUN", "无副作用演练");

  private final String code;
  private final String label;

  public static BatchDayReplayExecutionMode fromCodeOrNull(String value) {
    return DictEnum.fromCode(BatchDayReplayExecutionMode.class, value);
  }
}
