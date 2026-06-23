package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowNodeCode implements DictEnum {
  START("START", "开始"),
  END("END", "结束");

  private final String code;
  private final String label;
}
