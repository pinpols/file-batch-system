package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum LogType implements DictEnum {
  SYSTEM("SYSTEM", "系统日志"),
  BUSINESS("BUSINESS", "业务日志"),
  ALARM("ALARM", "告警日志");

  private final String code;
  private final String label;
}
