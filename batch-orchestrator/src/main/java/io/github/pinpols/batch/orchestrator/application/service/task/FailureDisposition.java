package io.github.pinpols.batch.orchestrator.application.service.task;

/**
 * 一次任务回报的失败处置结论，替代互斥的“是否取消”“是否安排重试”两个布尔参数。
 *
 * <p>取消优先于重试；成功路径两个标记都为假，得到 {@link #NOT_RETRIED}，与原两参数都为假时的走向一致。
 */
enum FailureDisposition {
  /** 取消请求下的 worker 取消回报。 */
  CANCELLED,
  /** 已由治理层写入重试计划。 */
  RETRY_SCHEDULED,
  /** 不重试：最终失败或成功路径。 */
  NOT_RETRIED;

  static FailureDisposition of(boolean cancellationOutcome, boolean retryScheduled) {
    if (cancellationOutcome) {
      return CANCELLED;
    }
    return retryScheduled ? RETRY_SCHEDULED : NOT_RETRIED;
  }
}
