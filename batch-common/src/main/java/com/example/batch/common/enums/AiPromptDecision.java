package com.example.batch.common.enums;

public enum AiPromptDecision {
  APPROVED("APPROVED", "通过"),
  REJECTED_SCOPE("REJECTED_SCOPE", "超出范围"),
  REJECTED_AUTH("REJECTED_AUTH", "权限不足"),
  REJECTED_DISABLED("REJECTED_DISABLED", "功能关闭"),
  REJECTED_SAFETY("REJECTED_SAFETY", "安全策略拒绝"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;

  AiPromptDecision(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }
}
