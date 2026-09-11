package io.github.pinpols.batch.common.enums;

import io.github.pinpols.batch.common.utils.EmptyChecks;
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

  private static final String LEGACY_USE_CURRENT_CONFIG = "USE_CURRENT_CONFIG";
  private static final String LEGACY_USE_SPECIFIC_VERSION = "USE_SPECIFIC_VERSION";

  /** 解析规范 code，并将早期 OpenAPI 暴露过的两个旧名称归一到当前语义。 */
  public static ConfigVersionPolicy fromCodeOrNull(String value) {
    String normalized = EmptyChecks.isNull(value) ? null : value.trim();
    ConfigVersionPolicy policy = DictEnum.fromCode(ConfigVersionPolicy.class, normalized);
    if (EmptyChecks.isNotNull(policy)) {
      return policy;
    }
    if (LEGACY_USE_CURRENT_CONFIG.equalsIgnoreCase(normalized)) {
      return USE_LATEST_CONFIG;
    }
    if (LEGACY_USE_SPECIFIC_VERSION.equalsIgnoreCase(normalized)) {
      return USE_SPECIFIED_VERSION;
    }
    return null;
  }
}
