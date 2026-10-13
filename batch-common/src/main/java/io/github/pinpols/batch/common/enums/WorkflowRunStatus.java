package io.github.pinpols.batch.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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

  private static final Set<String> TERMINAL_CODES =
      codes(status -> status.lifecycle().terminal());
  private static final Set<String> ACTIVE_CODES =
      codes(status -> !status.lifecycle().terminal());

  /** 投影到公共生命周期；PAUSED 是可恢复运行态，dry-run 终态按对应成功/失败归类。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case CREATED -> BatchLifecycleStatus.CREATED;
      case RUNNING, PAUSED -> BatchLifecycleStatus.RUNNING;
      case SUCCESS, SUCCESS_DRY_RUN -> BatchLifecycleStatus.SUCCESS;
      case FAILED, FAILED_DRY_RUN -> BatchLifecycleStatus.FAILED;
      case TERMINATED -> BatchLifecycleStatus.TERMINATED;
    };
  }

  public static Set<String> terminalCodes() {
    return TERMINAL_CODES;
  }

  public static Set<String> activeCodes() {
    return ACTIVE_CODES;
  }

  /** 是否 dry-run 终态(SUCCESS_DRY_RUN / FAILED_DRY_RUN)。 */
  public boolean isDryRunTerminal() {
    return this == SUCCESS_DRY_RUN || this == FAILED_DRY_RUN;
  }

  private static Set<String> codes(Predicate<WorkflowRunStatus> predicate) {
    return Arrays.stream(values())
        .filter(predicate)
        .map(WorkflowRunStatus::code)
        .collect(Collectors.toUnmodifiableSet());
  }
}
