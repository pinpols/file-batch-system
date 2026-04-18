package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum JobType implements DictEnum {
  GENERAL("GENERAL", "通用任务"),
  IMPORT("IMPORT", "导入任务"),
  EXPORT("EXPORT", "导出任务"),
  DISPATCH("DISPATCH", "分发任务"),
  WORKFLOW("WORKFLOW", "工作流任务");

  private final String code;
  private final String label;
}
