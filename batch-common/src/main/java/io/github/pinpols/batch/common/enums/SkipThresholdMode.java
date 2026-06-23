package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum SkipThresholdMode implements DictEnum {
  ABSOLUTE("ABSOLUTE", "绝对阈值"),
  PERCENTAGE("PERCENTAGE", "比例阈值");

  private final String code;
  private final String label;
}
