package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowRunStatus implements DictEnum {
  CREATED("CREATED", "已创建"),
  RUNNING("RUNNING", "执行中"),
  /** ADR-044 可逆暂停态:停发下游 DAG 节点,在途自然终结,resume 回 RUNNING。 */
  PAUSED("PAUSED", "已暂停"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  TERMINATED("TERMINATED", "已终止"),
  /** ADR-026 dry-run 演练终态：业务上等同 SUCCESS, 隔离指标 / audit。 */
  SUCCESS_DRY_RUN("SUCCESS_DRY_RUN", "演练成功"),
  /** ADR-026 dry-run 演练终态：业务上等同 FAILED, 隔离指标 / audit。 */
  FAILED_DRY_RUN("FAILED_DRY_RUN", "演练失败");

  private final String code;
  private final String label;

  /** 是否 dry-run 终态(SUCCESS_DRY_RUN / FAILED_DRY_RUN)。 */
  public boolean isDryRunTerminal() {
    return this == SUCCESS_DRY_RUN || this == FAILED_DRY_RUN;
  }
}
