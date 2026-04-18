package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ResourceQueueType implements DictEnum {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  DISPATCH("DISPATCH", "派发"),
  MIXED("MIXED", "混合");

  private final String code;
  private final String label;
}
