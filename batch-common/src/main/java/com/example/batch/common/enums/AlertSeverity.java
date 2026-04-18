package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum AlertSeverity implements DictEnum {
  INFO("INFO", "信息"),
  WARN("WARN", "警告"),
  ERROR("ERROR", "错误"),
  CRITICAL("CRITICAL", "严重");

  private final String code;
  private final String label;
}
