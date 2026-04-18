package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TriggerType implements DictEnum {
  API("API", "接口触发"),
  MANUAL("MANUAL", "手工触发"),
  EVENT("EVENT", "事件触发"),
  CATCH_UP("CATCH_UP", "补跑触发"),
  SCHEDULED("SCHEDULED", "定时触发");

  private final String code;
  private final String label;
}
