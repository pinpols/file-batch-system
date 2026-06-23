package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileDispatchRunStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  RUNNING("RUNNING", "执行中"),
  COMPENSATING("COMPENSATING", "补偿中"),
  ARCHIVED("ARCHIVED", "已归档");

  private final String code;
  private final String label;
}
