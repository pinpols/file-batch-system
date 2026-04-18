package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PipelineRunStatus implements DictEnum {
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;
}
