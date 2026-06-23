package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum RetryScheduleStatus implements DictEnum {
  WAITING("WAITING", "待重试"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  EXHAUSTED("EXHAUSTED", "已耗尽"),
  CANCELLED("CANCELLED", "已取消");

  private final String code;
  private final String label;
}
