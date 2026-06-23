package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum RetryPolicyType implements DictEnum {
  NONE("NONE", "不重试"),
  FIXED("FIXED", "固定间隔"),
  EXPONENTIAL("EXPONENTIAL", "指数退避");

  private final String code;
  private final String label;
}
