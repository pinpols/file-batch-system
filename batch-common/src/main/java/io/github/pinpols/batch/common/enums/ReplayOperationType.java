package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** Persisted scopes supported by an ADR-049 replay operation. */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ReplayOperationType implements DictEnum {
  BATCH_DAY_RANGE("BATCH_DAY_RANGE", "业务日区间"),
  WORKFLOW_NODE("WORKFLOW_NODE", "失败节点"),
  WORKFLOW_RUN("WORKFLOW_RUN", "整个 Workflow");

  private final String code;
  private final String label;
}
