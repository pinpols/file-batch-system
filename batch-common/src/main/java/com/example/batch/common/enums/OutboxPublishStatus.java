package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum OutboxPublishStatus {
  NEW("NEW", "待发送"),
  PUBLISHING("PUBLISHING", "发送中"),
  PUBLISHED("PUBLISHED", "已发送"),
  FAILED("FAILED", "发送失败"),
  GIVE_UP("GIVE_UP", "放弃发送");

  private final String code;
  private final String label;

  OutboxPublishStatus(String code, String label) {
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
        .map(OutboxPublishStatus::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
