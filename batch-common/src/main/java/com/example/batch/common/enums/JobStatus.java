package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum JobStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  WAITING("WAITING", "等待中"),
  READY("READY", "待执行"),
  RUNNING("RUNNING", "执行中"),
  PARTIAL_FAILED("PARTIAL_FAILED", "部分失败"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  CANCELLED("CANCELLED", "已取消"),
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;

  /** 投影到公共生命周期状态。PARTIAL_FAILED 归类为 FAILED 终态。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case CREATED -> BatchLifecycleStatus.CREATED;
      case WAITING -> BatchLifecycleStatus.WAITING;
      case READY -> BatchLifecycleStatus.READY;
      case RUNNING -> BatchLifecycleStatus.RUNNING;
      case SUCCESS -> BatchLifecycleStatus.SUCCESS;
      case PARTIAL_FAILED, FAILED -> BatchLifecycleStatus.FAILED;
      case CANCELLED -> BatchLifecycleStatus.CANCELLED;
      case TERMINATED -> BatchLifecycleStatus.TERMINATED;
    };
  }
}
