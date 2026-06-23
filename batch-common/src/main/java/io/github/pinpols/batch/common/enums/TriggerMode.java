package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TriggerMode implements DictEnum {
  SCHEDULED("SCHEDULED", "定时"),
  API("API", "API"),
  MANUAL("MANUAL", "手动"),
  EVENT("EVENT", "事件"),
  MIXED("MIXED", "混合");

  private final String code;
  private final String label;
}
