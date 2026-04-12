package com.example.batch.common.enums;

public enum ErrorSinkType {
  ERROR_TABLE("ERROR_TABLE", "错误表"),
  ERROR_FILE("ERROR_FILE", "错误文件"),
  BOTH("BOTH", "同时落表和落文件");

  private final String code;
  private final String label;

  ErrorSinkType(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public static ErrorSinkType fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (ErrorSinkType sinkType : values()) {
      if (sinkType.code.equalsIgnoreCase(code.trim())) {
        return sinkType;
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
