package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum OutOfWindowAction implements DictEnum {
  WAIT("WAIT", "等待下次窗口"),
  FAIL("FAIL", "失败");

  private final String code;
  private final String label;
}
