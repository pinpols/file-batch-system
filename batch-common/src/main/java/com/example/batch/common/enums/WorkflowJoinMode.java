package com.example.batch.common.enums;

public enum WorkflowJoinMode {
  ALL("ALL", "全部满足后汇聚"),
  ANY("ANY", "任一满足即汇聚"),
  N_OF("N_OF", "满足指定数量后汇聚");

  private final String code;
  private final String label;

  WorkflowJoinMode(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  /** L-3: 根据 code 解析 JoinMode；未知 code 直接抛异常，避免配置错误被静默吞掉。 */
  public static WorkflowJoinMode fromCode(String code) {
    if (code == null || code.isBlank()) {
      return ALL;
    }
    for (WorkflowJoinMode value : values()) {
      if (value.code.equalsIgnoreCase(code)) {
        return value;
      }
    }
    throw new IllegalArgumentException("Unknown WorkflowJoinMode code: '" + code + "'");
  }

  /** 安全变体：未知 code 时返回 ALL，用于读取可能含历史废弃模式的旧数据。 */
  public static WorkflowJoinMode fromCodeOrDefault(String code) {
    if (code == null || code.isBlank()) {
      return ALL;
    }
    for (WorkflowJoinMode value : values()) {
      if (value.code.equalsIgnoreCase(code)) {
        return value;
      }
    }
    return ALL;
  }
}
