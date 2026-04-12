package com.example.batch.common.enums;

public enum SkipThresholdMode {
  ABSOLUTE("ABSOLUTE", "绝对阈值"),
  PERCENTAGE("PERCENTAGE", "比例阈值");

  private final String code;
  private final String label;

  SkipThresholdMode(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public static SkipThresholdMode fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (SkipThresholdMode mode : values()) {
      if (mode.code.equalsIgnoreCase(code.trim())) {
        return mode;
      }
    }
    return null;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }
}
