package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum PartitionStatus {
  CREATED("CREATED", "已创建"),
  WAITING("WAITING", "等待中"),
  READY("READY", "待领取"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  RETRYING("RETRYING", "重试中"),
  CANCELLED("CANCELLED", "已取消"),
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;

  PartitionStatus(String code, String label) {
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
        .map(PartitionStatus::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
