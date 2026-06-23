package io.github.pinpols.batch.common.enums;

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
  /** ADR-044 可逆暂停态:停发新分区、在途自然终结,resume 回 RUNNING。 */
  PAUSED("PAUSED", "已暂停"),
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
      // PAUSED 是可逆非终态;对外生命周期投影归入 RUNNING(仍在运行生命周期内,只是暂停派发)。
      case PAUSED -> BatchLifecycleStatus.RUNNING;
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

  /**
   * R4-Flyway-2 / S1-5：从 code 字符串解析为枚举；未知 code 返回 {@code null}（不抛）。
   *
   * <p>关键 rolling-rollback 兼容性：V117 增加了 SUCCESS_DRY_RUN / FAILED_DRY_RUN 后， 一旦回退到不含这两值的旧镜像，旧代码读到 DB
   * 里 dry-run 状态行，{@link DictEnum#fromCode} 返回 null。 调用方必须用 null-safe 路径（如 lifecycle 推断走 {@link
   * BatchLifecycleStatus#UNKNOWN}）回退， 不允许直接对结果 {@code .lifecycle()} 解引用导致 NPE。
   */
  public static JobInstanceStatus fromCodeOrNull(String code) {
    return DictEnum.fromCode(JobInstanceStatus.class, code);
  }
}
