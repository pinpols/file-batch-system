package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum AlertSeverity {
  INFO("INFO", "信息"),
  WARN("WARN", "警告"),
  ERROR("ERROR", "错误"),
  CRITICAL("CRITICAL", "严重");

  private final String code;
  private final String label;

  AlertSeverity(String code, String label) {
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
    return Arrays.stream(values()).map(AlertSeverity::code).collect(Collectors.toUnmodifiableSet());
  }
}
