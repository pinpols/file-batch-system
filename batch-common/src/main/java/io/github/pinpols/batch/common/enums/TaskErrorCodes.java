package io.github.pinpols.batch.common.enums;

/** Worker 与 Orchestrator 共用的稳定任务执行错误码。 */
public final class TaskErrorCodes {

  /** 非幂等外部动作可能已经提交，但无法确认最终结果。 */
  public static final String EXTERNAL_OUTCOME_UNKNOWN = "EXTERNAL_OUTCOME_UNKNOWN";

  /** 必需的冻结模板缺失、未经认证或与当前任务身份不符。 */
  public static final String FROZEN_FILE_TEMPLATE_INVALID = "FROZEN_FILE_TEMPLATE_INVALID";

  /** Worker 运行期解析的重放能力不满足冻结操作合同。 */
  public static final String REPLAY_CAPABILITY_INVALID = "REPLAY_CAPABILITY_INVALID";

  private TaskErrorCodes() {}
}
