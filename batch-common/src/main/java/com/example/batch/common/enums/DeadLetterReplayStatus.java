package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum DeadLetterReplayStatus implements DictEnum {
  NEW("NEW", "新建"),
  REPLAYING("REPLAYING", "重放中"),
  SUCCESS("SUCCESS", "重放成功"),
  FAILED("FAILED", "重放失败"),
  GIVE_UP("GIVE_UP", "放弃处理");

  private final String code;
  private final String label;
}
