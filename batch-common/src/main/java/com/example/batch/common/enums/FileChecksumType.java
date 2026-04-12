package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileChecksumType {
  NONE("NONE", "无"),
  MD5("MD5", "MD5"),
  SHA_256("SHA-256", "SHA-256");

  private final String code;
  private final String label;

  FileChecksumType(String code, String label) {
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
        .map(FileChecksumType::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
