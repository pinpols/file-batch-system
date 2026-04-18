package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileStatus implements DictEnum {
  RECEIVED("RECEIVED", "已接收"),
  PARSING("PARSING", "解析中"),
  PARSED("PARSED", "已解析"),
  VALIDATED("VALIDATED", "已校验"),
  LOADED("LOADED", "已加载"),
  GENERATED("GENERATED", "已生成"),
  DISPATCHING("DISPATCHING", "分发中"),
  DISPATCHED("DISPATCHED", "已分发"),
  ARCHIVED("ARCHIVED", "已归档"),
  FAILED("FAILED", "失败"),
  DELETED("DELETED", "已删除");

  private final String code;
  private final String label;

  /** 空白返回 null；未知 code 抛 IllegalArgumentException。 */
  public static FileStatus fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    FileStatus match = DictEnum.fromCode(FileStatus.class, code);
    if (match == null) {
      throw new IllegalArgumentException("Unknown FileStatus code: '" + code + "'");
    }
    return match;
  }
}
