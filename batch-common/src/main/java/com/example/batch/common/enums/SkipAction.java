package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum SkipAction implements DictEnum {
  CONTINUE("CONTINUE", "继续"),
  FAIL_BATCH("FAIL_BATCH", "失败整批"),
  MANUAL_REVIEW("MANUAL_REVIEW", "人工复核");

  private final String code;
  private final String label;
}
