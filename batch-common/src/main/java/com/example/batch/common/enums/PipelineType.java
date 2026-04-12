package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum PipelineType {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  DISPATCH("DISPATCH", "派发");

  private final String code;
  private final String label;

  PipelineType(String code, String label) {
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
    return Arrays.stream(values()).map(PipelineType::code).collect(Collectors.toUnmodifiableSet());
  }
}
