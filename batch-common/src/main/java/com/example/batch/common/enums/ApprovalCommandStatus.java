package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ApprovalCommandStatus implements DictEnum {
  PENDING("PENDING", "待审批"),
  APPROVED("APPROVED", "已通过"),
  REJECTED("REJECTED", "已拒绝"),
  EXECUTED("EXECUTED", "已执行");

  private final String code;
  private final String label;
}
