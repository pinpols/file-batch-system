package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum HolidayRollRule {
  SKIP("SKIP", "跳过"),
  NEXT_WORKDAY("NEXT_WORKDAY", "顺延至下个工作日"),
  PREV_WORKDAY("PREV_WORKDAY", "提前至上个工作日");

  private final String code;
  private final String label;

  HolidayRollRule(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  public static Set<String> codes() {
    return Arrays.stream(values())
        .map(HolidayRollRule::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
