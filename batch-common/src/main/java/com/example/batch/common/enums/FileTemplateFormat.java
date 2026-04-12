package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileTemplateFormat {
  DELIMITED("DELIMITED", "分隔符"),
  FIXED_WIDTH("FIXED_WIDTH", "固定宽度"),
  EXCEL("EXCEL", "Excel"),
  XML("XML", "XML"),
  JSON("JSON", "JSON"),
  BINARY("BINARY", "二进制");

  private final String code;
  private final String label;

  FileTemplateFormat(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  public static Set<String> codes() {
    return Arrays.stream(values())
        .map(FileTemplateFormat::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
