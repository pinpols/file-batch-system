package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum AiPromptDecision implements DictEnum {
  APPROVED("APPROVED", "通过"),
  REJECTED_SCOPE("REJECTED_SCOPE", "超出范围"),
  REJECTED_AUTH("REJECTED_AUTH", "权限不足"),
  REJECTED_DISABLED("REJECTED_DISABLED", "功能关闭"),
  REJECTED_SAFETY("REJECTED_SAFETY", "安全策略拒绝"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;
}
