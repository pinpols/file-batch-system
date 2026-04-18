package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowEdgeType implements DictEnum {
  SUCCESS("SUCCESS", "成功"),
  FAILURE("FAILURE", "失败"),
  CONDITION("CONDITION", "条件"),
  ALWAYS("ALWAYS", "总是");

  private final String code;
  private final String label;
}
