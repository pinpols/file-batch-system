package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileDispatchStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  SENT("SENT", "已发送"),
  ACKED("ACKED", "已确认"),
  FAILED("FAILED", "失败"),
  COMPENSATED("COMPENSATED", "已补偿");

  private final String code;
  private final String label;
}
