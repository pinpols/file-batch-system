package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum OutboxPublishStatus implements DictEnum {
  NEW("NEW", "待发送"),
  PUBLISHING("PUBLISHING", "发送中"),
  PUBLISHED("PUBLISHED", "已发送"),
  FAILED("FAILED", "发送失败"),
  GIVE_UP("GIVE_UP", "放弃发送");

  private final String code;
  private final String label;
}
