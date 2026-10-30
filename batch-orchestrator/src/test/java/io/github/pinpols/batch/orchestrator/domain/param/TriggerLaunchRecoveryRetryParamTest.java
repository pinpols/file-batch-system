package io.github.pinpols.batch.orchestrator.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("启动恢复重试参数的必填字段")
class TriggerLaunchRecoveryRetryParamTest {
  private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

  @Test
  @DisplayName("身份代际错误和时间字段必须提供")
  void shouldRequireIdentityGenerationErrorAndTime() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryRetryParam(null, "r", 0L, "HTTP_503", NOW, 5, 30, 900))
        .withMessage("tenantId");
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryRetryParam("t", null, 0L, "HTTP_503", NOW, 5, 30, 900))
        .withMessage("requestId");
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryRetryParam("t", "r", null, "HTTP_503", NOW, 5, 30, 900))
        .withMessage("recoveryGeneration");
    assertThatNullPointerException()
        .isThrownBy(() -> new TriggerLaunchRecoveryRetryParam("t", "r", 0L, null, NOW, 5, 30, 900))
        .withMessage("errorCode");
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryRetryParam("t", "r", 0L, "HTTP_503", null, 5, 30, 900))
        .withMessage("now");
  }

  @Test
  @DisplayName("保留配置的重试与退避参数")
  void shouldPreserveConfigurationValues() {
    TriggerLaunchRecoveryRetryParam param =
        new TriggerLaunchRecoveryRetryParam("t", "r", 0L, "HTTP_503", NOW, 0, 0, 1);
    assertThat(param.maxAttempts()).isZero();
    assertThat(param.baseBackoffSeconds()).isZero();
  }
}
