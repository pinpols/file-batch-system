package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowType implements DictEnum {
  DAG("DAG", "DAG"),
  PIPELINE("PIPELINE", "流水线"),
  MIXED("MIXED", "混合");

  private final String code;
  private final String label;
}
