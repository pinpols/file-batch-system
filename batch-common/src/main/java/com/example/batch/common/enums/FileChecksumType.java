package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileChecksumType implements DictEnum {
  NONE("NONE", "无"),
  MD5("MD5", "MD5"),
  SHA_256("SHA-256", "SHA-256");

  private final String code;
  private final String label;
}
