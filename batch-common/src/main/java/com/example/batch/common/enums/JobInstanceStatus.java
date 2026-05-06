package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum JobInstanceStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  WAITING("WAITING", "等待中"),
  READY("READY", "待执行"),
  RUNNING("RUNNING", "执行中"),
  PARTIAL_FAILED("PARTIAL_FAILED", "部分失败"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  CANCELLED("CANCELLED", "已取消"),
  TERMINATED("TERMINATED", "已终止"),
  /** ADR-026 dry-run 演练终态：业务上等同 SUCCESS, 但隔离指标 / audit / result_version 不进 EFFECTIVE 链。 */
  SUCCESS_DRY_RUN("SUCCESS_DRY_RUN", "演练成功"),
  /** ADR-026 dry-run 演练终态：业务上等同 FAILED, 但隔离指标 / audit。 */
  FAILED_DRY_RUN("FAILED_DRY_RUN", "演练失败");

  private final String code;
  private final String label;

  /** 投影到公共生命周期状态。PARTIAL_FAILED / FAILED_DRY_RUN 归类为 FAILED, SUCCESS_DRY_RUN 归类为 SUCCESS 终态。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case CREATED -> BatchLifecycleStatus.CREATED;
      case WAITING -> BatchLifecycleStatus.WAITING;
      case READY -> BatchLifecycleStatus.READY;
      case RUNNING -> BatchLifecycleStatus.RUNNING;
      case SUCCESS, SUCCESS_DRY_RUN -> BatchLifecycleStatus.SUCCESS;
      case PARTIAL_FAILED, FAILED, FAILED_DRY_RUN -> BatchLifecycleStatus.FAILED;
      case CANCELLED -> BatchLifecycleStatus.CANCELLED;
      case TERMINATED -> BatchLifecycleStatus.TERMINATED;
    };
  }

  /** 是否 dry-run 终态(SUCCESS_DRY_RUN / FAILED_DRY_RUN)。 */
  public boolean isDryRunTerminal() {
    return this == SUCCESS_DRY_RUN || this == FAILED_DRY_RUN;
  }
}
