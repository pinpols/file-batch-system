package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum HolidayRollRule implements DictEnum {
  SKIP("SKIP", "跳过"),
  NEXT_WORKDAY("NEXT_WORKDAY", "顺延至下个工作日"),
  PREV_WORKDAY("PREV_WORKDAY", "提前至上个工作日");

  private final String code;
  private final String label;
}
