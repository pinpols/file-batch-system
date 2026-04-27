package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum PartitionStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  WAITING("WAITING", "等待中"),
  READY("READY", "待领取"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  RETRYING("RETRYING", "重试中"),
  CANCELLED("CANCELLED", "已取消"),
  TERMINATED("TERMINATED", "已终止");

  private final String code;
  private final String label;

  /** 投影到公共生命周期状态。RETRYING 归类为 RUNNING（仍在推进中）。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case CREATED -> BatchLifecycleStatus.CREATED;
      case WAITING -> BatchLifecycleStatus.WAITING;
      case READY -> BatchLifecycleStatus.READY;
      case RUNNING, RETRYING -> BatchLifecycleStatus.RUNNING;
      case SUCCESS -> BatchLifecycleStatus.SUCCESS;
      case FAILED -> BatchLifecycleStatus.FAILED;
      case CANCELLED -> BatchLifecycleStatus.CANCELLED;
      case TERMINATED -> BatchLifecycleStatus.TERMINATED;
    };
  }
}
