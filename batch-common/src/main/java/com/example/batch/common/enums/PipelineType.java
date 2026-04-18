package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PipelineType implements DictEnum {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  DISPATCH("DISPATCH", "派发");

  private final String code;
  private final String label;
}
