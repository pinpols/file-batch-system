package io.github.pinpols.batch.common.enums;

import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum RunMode implements DictEnum {
  NORMAL("NORMAL", "正常执行"),
  RETRY("RETRY", "系统重试"),
  RERUN("RERUN", "人工重跑"),
  RECOVER("RECOVER", "故障恢复"),
  COMPENSATE("COMPENSATE", "业务补偿");

  private final String code;
  private final String label;

  public static Optional<RunMode> fromCode(String value) {
    return Optional.ofNullable(DictEnum.fromCode(RunMode.class, value));
  }
}
