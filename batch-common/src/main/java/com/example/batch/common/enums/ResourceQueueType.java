package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum ResourceQueueType {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  DISPATCH("DISPATCH", "派发"),
  MIXED("MIXED", "混合");

  private final String code;
  private final String label;

  ResourceQueueType(String code, String label) {
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
        .map(ResourceQueueType::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
