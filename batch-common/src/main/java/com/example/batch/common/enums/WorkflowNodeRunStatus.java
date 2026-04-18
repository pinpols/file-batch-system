package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowNodeRunStatus implements DictEnum {
  READY("READY", "待执行"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  SKIPPED("SKIPPED", "跳过");

  private final String code;
  private final String label;
}
