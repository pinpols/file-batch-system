package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ShardStrategy implements DictEnum {
  NONE("NONE", "不分片"),
  STATIC("STATIC", "静态分片"),
  DYNAMIC("DYNAMIC", "动态分片"),
  AUTO("AUTO", "自动分片");

  private final String code;
  private final String label;

  /** 空白或未知 code 回落到 NONE。 */
  public static ShardStrategy fromCode(String code) {
    ShardStrategy match = DictEnum.fromCode(ShardStrategy.class, code);
    return match != null ? match : NONE;
  }
}
