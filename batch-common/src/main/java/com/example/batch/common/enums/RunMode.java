package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Optional;

public enum RunMode {
  NORMAL("NORMAL", "正常执行"),
  RETRY("RETRY", "系统重试"),
  RERUN("RERUN", "人工重跑"),
  RECOVER("RECOVER", "故障恢复"),
  COMPENSATE("COMPENSATE", "业务补偿");

  private final String code;
  private final String label;

  RunMode(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  public static Optional<RunMode> fromCode(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Arrays.stream(values())
        .filter(candidate -> candidate.code.equalsIgnoreCase(value.trim()))
        .findFirst();
  }
}
