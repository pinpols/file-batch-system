package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileTemplateType {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  SHARED("SHARED", "共享");

  private final String code;
  private final String label;

  FileTemplateType(String code, String label) {
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
        .map(FileTemplateType::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
