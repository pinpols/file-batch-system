package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum RetryPolicyType {
  NONE("NONE", "不重试"),
  FIXED("FIXED", "固定间隔"),
  EXPONENTIAL("EXPONENTIAL", "指数退避");

  private final String code;
  private final String label;

  RetryPolicyType(String code, String label) {
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
        .map(RetryPolicyType::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
