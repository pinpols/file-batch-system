package io.github.pinpols.batch.worker.core.support;

/**
 * 安全增量补偿的反向动作结果。
 *
 * @param outcome 结果类别(REVERSED / SKIPPED / FAILED)
 * @param reversedCount 实际反向(删行 / 删对象)的数量;SKIPPED / FAILED 时通常为 0
 * @param detail 人类可读的明细(删了哪张表 / 哪些对象 / SKIPPED 原因 / FAILED 原因),落审计
 */
public record CompensationResult(Outcome outcome, long reversedCount, String detail) {

  public enum Outcome {
    /** 成功执行了反向动作(可能删了 0 行,但确实安全 scope 并执行了 DELETE)。 */
    REVERSED,
    /** 无法精确 scope(如模板没绑 run 标识列)→ 安全起见不删,记原因。 */
    SKIPPED,
    /** 反向动作执行中出错(best-effort) → 记原因;不掩盖原始失败。 */
    FAILED
  }

  public static CompensationResult reversed(long reversedCount, String detail) {
    return new CompensationResult(Outcome.REVERSED, reversedCount, detail);
  }

  public static CompensationResult skipped(String detail) {
    return new CompensationResult(Outcome.SKIPPED, 0L, detail);
  }

  public static CompensationResult failed(String detail) {
    return new CompensationResult(Outcome.FAILED, 0L, detail);
  }
}
