package io.github.pinpols.batch.worker.atomic.runtime;

import java.math.BigDecimal;

/** 原子任务数字参数的精确整数转换。 */
public final class AtomicParameterNumbers {

  private AtomicParameterNumbers() {}

  public static Long positiveLong(Object value) {
    if (!(value instanceof Number number)) {
      return null;
    }
    try {
      long parsed = new BigDecimal(number.toString()).longValueExact();
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException | ArithmeticException invalid) {
      return null;
    }
  }
}
