package io.github.pinpols.batch.orchestrator.domain.scheduling;

import java.time.Instant;

/**
 * 跨日历依赖等待的超时告警判定：从首次进入等待起算，超时阈值不大于 0 表示关闭；每条等待只告警一次，
 * 由调用方随后用 claim 保证并发下也只发一次。
 */
public final class DependencyWaitTimeoutRule {

  private DependencyWaitTimeoutRule() {}

  public static boolean isAlertDue(
      Instant firstWaitingAt, Instant timeoutAlertedAt, long timeoutSeconds, Instant now) {
    if (firstWaitingAt == null || timeoutAlertedAt != null || timeoutSeconds <= 0) {
      return false;
    }
    Instant timeoutAt = firstWaitingAt.plusSeconds(timeoutSeconds);
    return !now.isBefore(timeoutAt);
  }
}
