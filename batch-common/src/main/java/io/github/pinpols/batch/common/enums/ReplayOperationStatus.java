package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** Durable user-visible lifecycle of an ADR-049 replay operation. */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ReplayOperationStatus implements DictEnum {
  PENDING_APPROVAL("PENDING_APPROVAL", "待审批"),
  REJECTED("REJECTED", "已拒绝"),
  RUNNING("RUNNING", "执行中"),
  SUCCEEDED("SUCCEEDED", "成功"),
  PARTIAL_FAILED("PARTIAL_FAILED", "部分失败"),
  FAILED("FAILED", "失败"),
  CANCELLED("CANCELLED", "已取消");

  private final String code;
  private final String label;
}
