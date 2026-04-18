package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum CompensationCommandStatus implements DictEnum {
  PENDING("PENDING", "待执行"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  CANCELLED("CANCELLED", "已取消");

  private final String code;
  private final String label;
}
