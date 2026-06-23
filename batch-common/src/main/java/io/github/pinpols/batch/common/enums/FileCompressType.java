package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileCompressType implements DictEnum {
  NONE("NONE", "无压缩"),
  ZIP("ZIP", "ZIP"),
  GZIP("GZIP", "GZIP");

  private final String code;
  private final String label;
}
