package io.github.pinpols.batch.orchestrator.domain.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("跨日历依赖等待超时判定:首次等待起算、到点即告警、已告警或关闭时不再告警")
class DependencyWaitTimeoutRuleTest {

  private static final Instant FIRST_WAITING_AT = Instant.parse("2026-11-04T00:00:00Z");

  @Test
  @DisplayName("到达首次等待加阈值的时刻即判定超时，早一秒不判定")
  void shouldBecomeDue_exactlyAtTimeoutBoundary() {
    Instant boundary = FIRST_WAITING_AT.plusSeconds(600);

    assertThat(DependencyWaitTimeoutRule.isAlertDue(
            FIRST_WAITING_AT, null, 600, boundary.minusSeconds(1)))
        .isFalse();
    assertThat(DependencyWaitTimeoutRule.isAlertDue(FIRST_WAITING_AT, null, 600, boundary))
        .isTrue();
  }

  @Test
  @DisplayName("已告警、缺少首次等待时间或阈值关闭时均不告警")
  void shouldNotAlert_whenAlreadyAlertedUnknownStartOrDisabled() {
    Instant late = FIRST_WAITING_AT.plusSeconds(3600);

    assertThat(DependencyWaitTimeoutRule.isAlertDue(FIRST_WAITING_AT, late, 600, late))
        .isFalse();
    assertThat(DependencyWaitTimeoutRule.isAlertDue(null, null, 600, late)).isFalse();
    assertThat(DependencyWaitTimeoutRule.isAlertDue(FIRST_WAITING_AT, null, 0, late))
        .isFalse();
    assertThat(DependencyWaitTimeoutRule.isAlertDue(FIRST_WAITING_AT, null, -1, late))
        .isFalse();
  }
}
