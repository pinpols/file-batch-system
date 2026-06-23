package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileTemplateType implements DictEnum {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  SHARED("SHARED", "共享");

  private final String code;
  private final String label;
}
