package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 补跑结果版本策略。持久化和接口传输使用 {@link #code()}。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ResultVersionPolicy implements DictEnum {
  CREATE_NEW_VERSION("CREATE_NEW_VERSION", "创建新结果版本"),
  KEEP_BOTH("KEEP_BOTH", "同时保留新旧版本"),
  MANUAL_CONFIRM_EFFECTIVE("MANUAL_CONFIRM_EFFECTIVE", "人工确认后生效"),
  /** 批量日 dry-run 内部策略；禁止进入 EFFECTIVE 链。 */
  DRY_RUN_ONLY("DRY_RUN_ONLY", "仅保留演练结果");

  private final String code;
  private final String label;

  public static ResultVersionPolicy fromCodeOrNull(String value) {
    return DictEnum.fromCode(ResultVersionPolicy.class, value);
  }
}
