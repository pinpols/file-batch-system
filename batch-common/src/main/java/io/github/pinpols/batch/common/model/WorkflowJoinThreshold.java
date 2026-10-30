package io.github.pinpols.batch.common.model;

import java.math.BigDecimal;

/** Workflow 汇聚阈值的精确整数解析,不将小数或溢出值截断。 */
public final class WorkflowJoinThreshold {
  private WorkflowJoinThreshold() {}

  /** 数值接受数学整数, 文本仅接受整数形式;非法输入返回 null。 */
  public static Integer parse(Object value) {
    try {
      if (value instanceof Number number) {
        return new BigDecimal(number.toString()).intValueExact();
      }
      if (value instanceof String text) {
        return Integer.valueOf(text);
      }
      return null;
    } catch (NumberFormatException | ArithmeticException invalid) {
      return null;
    }
  }
}
