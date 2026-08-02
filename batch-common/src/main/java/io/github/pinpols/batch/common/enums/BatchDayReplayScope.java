package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 批量日重放的候选范围。持久化和接口传输使用 {@link #code()}。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchDayReplayScope implements DictEnum {
  ALL("ALL", "全部实例"),
  ALL_FAILED("ALL_FAILED", "全部失败实例"),
  SUBSET_JOB_CODES("SUBSET_JOB_CODES", "指定作业"),
  OUTPUTS_ONLY("OUTPUTS_ONLY", "仅提升结果版本");

  private final String code;
  private final String label;

  public static BatchDayReplayScope fromCodeOrNull(String value) {
    return DictEnum.fromCode(BatchDayReplayScope.class, value);
  }
}
