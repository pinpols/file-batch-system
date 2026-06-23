package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ErrorSinkType implements DictEnum {
  ERROR_TABLE("ERROR_TABLE", "错误表"),
  ERROR_FILE("ERROR_FILE", "错误文件"),
  BOTH("BOTH", "同时落表和落文件");

  private final String code;
  private final String label;
}
