package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileEncryptType implements DictEnum {
  NONE("NONE", "无加密"),
  AES("AES", "AES"),
  PGP("PGP", "PGP"),
  CUSTOM("CUSTOM", "自定义");

  private final String code;
  private final String label;
}
