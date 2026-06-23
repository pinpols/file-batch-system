package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum SchedulingPriorityBand implements DictEnum {
  HIGH("HIGH", "高"),
  MEDIUM("MEDIUM", "中"),
  LOW("LOW", "低");

  private final String code;
  private final String label;
}
