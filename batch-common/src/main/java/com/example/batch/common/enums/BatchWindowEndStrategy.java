package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum BatchWindowEndStrategy {
  STOP("STOP", "立即停止"),
  FINISH_RUNNING("FINISH_RUNNING", "等待运行中任务完成"),
  CONTINUE("CONTINUE", "继续运行");

  private final String code;
  private final String label;

  BatchWindowEndStrategy(String code, String label) {
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
        .map(BatchWindowEndStrategy::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
