package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TaskStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  READY("READY", "待执行"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  CANCELLED("CANCELLED", "已取消"),
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;

  /** 投影到公共生命周期状态。TaskStatus 没有 WAITING 态（worker 视角拿到 task 时已 READY）。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case CREATED -> BatchLifecycleStatus.CREATED;
      case READY -> BatchLifecycleStatus.READY;
      case RUNNING -> BatchLifecycleStatus.RUNNING;
      case SUCCESS -> BatchLifecycleStatus.SUCCESS;
      case FAILED -> BatchLifecycleStatus.FAILED;
      case CANCELLED -> BatchLifecycleStatus.CANCELLED;
      case TERMINATED -> BatchLifecycleStatus.TERMINATED;
    };
  }
}
