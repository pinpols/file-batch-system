package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileTemplateFormat implements DictEnum {
  DELIMITED("DELIMITED", "分隔符"),
  FIXED_WIDTH("FIXED_WIDTH", "固定宽度"),
  EXCEL("EXCEL", "Excel"),
  XML("XML", "XML"),
  JSON("JSON", "JSON"),
  BINARY("BINARY", "二进制");

  private final String code;
  private final String label;
}
