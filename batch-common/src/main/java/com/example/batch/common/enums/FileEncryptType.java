package com.example.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum FileEncryptType {
  NONE("NONE", "无加密"),
  AES("AES", "AES"),
  PGP("PGP", "PGP"),
  CUSTOM("CUSTOM", "自定义");

  private final String code;
  private final String label;

  FileEncryptType(String code, String label) {
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
        .map(FileEncryptType::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
