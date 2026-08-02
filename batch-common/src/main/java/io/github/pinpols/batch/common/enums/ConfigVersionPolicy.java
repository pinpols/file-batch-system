package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 补跑配置版本策略。持久化和接口传输使用 {@link #code()}。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ConfigVersionPolicy implements DictEnum {
  USE_ORIGINAL_CONFIG("USE_ORIGINAL_CONFIG", "使用原配置"),
  USE_LATEST_CONFIG("USE_LATEST_CONFIG", "使用最新配置"),
  USE_SPECIFIED_VERSION("USE_SPECIFIED_VERSION", "使用指定版本");

  private final String code;
  private final String label;

  public static ConfigVersionPolicy fromCodeOrNull(String value) {
    return DictEnum.fromCode(ConfigVersionPolicy.class, value);
  }
}
