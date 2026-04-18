package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ScheduleType implements DictEnum {
  CRON("CRON", "Cron 表达式"),
  FIXED_RATE("FIXED_RATE", "固定频率"),
  MANUAL("MANUAL", "手动");

  private final String code;
  private final String label;
}
