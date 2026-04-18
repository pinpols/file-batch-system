package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum CatchUpPolicyType implements DictEnum {
  NONE("NONE", "不补跑"),
  AUTO("AUTO", "自动补跑"),
  MANUAL_APPROVAL("MANUAL_APPROVAL", "人工审批");

  private final String code;
  private final String label;

  /** 空白回落到 NONE；未知 code 抛异常，避免配置错误被静默吞掉（M-10）。 */
  public static CatchUpPolicyType fromCode(String code) {
    if (code == null || code.isBlank()) {
      return NONE;
    }
    CatchUpPolicyType match = DictEnum.fromCode(CatchUpPolicyType.class, code);
    if (match == null) {
      throw new IllegalArgumentException("Unknown CatchUpPolicyType code: '" + code + "'");
    }
    return match;
  }

  /** 安全变体：空白或未知 code 时返回 NONE。 */
  public static CatchUpPolicyType fromCodeOrDefault(String code) {
    CatchUpPolicyType match = DictEnum.fromCode(CatchUpPolicyType.class, code);
    return match != null ? match : NONE;
  }
}
