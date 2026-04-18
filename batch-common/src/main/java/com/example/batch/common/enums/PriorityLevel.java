package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PriorityLevel implements DictEnum {
  P1("P1", "最高优先级"),
  P2("P2", "高优先级"),
  P3("P3", "较高优先级"),
  P4("P4", "偏高优先级"),
  P5("P5", "标准优先级"),
  P6("P6", "偏低优先级"),
  P7("P7", "较低优先级"),
  P8("P8", "低优先级"),
  P9("P9", "最低优先级");

  private final String code;
  private final String label;
}
