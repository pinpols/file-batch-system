package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TriggerStatus implements DictEnum {
  NORMAL("NORMAL", "正常"),
  REGISTERED("REGISTERED", "已注册"),
  PAUSED("PAUSED", "已暂停"),
  UNREGISTERED("UNREGISTERED", "已注销"),
  ERROR("ERROR", "异常"),
  BLOCKED("BLOCKED", "阻塞"),
  COMPLETE("COMPLETE", "已完成");

  private final String code;
  private final String label;
}
