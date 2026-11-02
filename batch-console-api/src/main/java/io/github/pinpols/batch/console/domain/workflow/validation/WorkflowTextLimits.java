package io.github.pinpols.batch.console.domain.workflow.validation;

/** Workflow 图文本字段的数据库字符数上限。 */
public final class WorkflowTextLimits {
  public static final int NODE_NAME = 256;
  public static final int CONDITION_EXPR = 1024;

  private WorkflowTextLimits() {}

  /** PostgreSQL 的 varchar 上限按字符计数，不能用 Java UTF-16 长度判断。 */
  public static boolean exceeds(String value, int maxCodePoints) {
    return value != null && value.codePointCount(0, value.length()) > maxCodePoints;
  }
}
