package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("失败处置：由取消标记与重试标记推导单一处置")
class FailureDispositionTest {

  @Test
  @DisplayName("仅取消时为已取消")
  void of_cancellationOnly_returnsCancelled() {
    assertThat(FailureDisposition.of(true, false)).isEqualTo(FailureDisposition.CANCELLED);
  }

  @Test
  @DisplayName("仅安排重试时为已安排重试")
  void of_retryOnly_returnsRetryScheduled() {
    assertThat(FailureDisposition.of(false, true)).isEqualTo(FailureDisposition.RETRY_SCHEDULED);
  }

  @Test
  @DisplayName("既未取消也未重试时为不重试（含成功路径）")
  void of_neither_returnsNotRetried() {
    assertThat(FailureDisposition.of(false, false)).isEqualTo(FailureDisposition.NOT_RETRIED);
  }

  @Test
  @DisplayName("取消与重试同时为真时取消优先")
  void of_cancellationAndRetry_cancellationTakesPrecedence() {
    assertThat(FailureDisposition.of(true, true)).isEqualTo(FailureDisposition.CANCELLED);
  }
}
