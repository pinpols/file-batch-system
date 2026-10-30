package io.github.pinpols.batch.orchestrator.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("启动恢复状态更新参数的必填字段")
class TriggerLaunchRecoveryAttemptParamTest {
  @Test
  @DisplayName("身份代际和状态字段必须提供")
  void shouldRequireIdentityGenerationAndStatus() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryAttemptParam(null, "r", 0L, "REJECTED", null, null))
        .withMessage("tenantId");
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryAttemptParam("t", null, 0L, "REJECTED", null, null))
        .withMessage("requestId");
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TriggerLaunchRecoveryAttemptParam("t", "r", null, "REJECTED", null, null))
        .withMessage("recoveryGeneration");
    assertThatNullPointerException()
        .isThrownBy(() -> new TriggerLaunchRecoveryAttemptParam("t", "r", 0L, null, null, null))
        .withMessage("status");
  }

  @Test
  @DisplayName("允许可选时间和错误字段为空")
  void shouldAllowOptionalTimeAndError() {
    TriggerLaunchRecoveryAttemptParam param =
        new TriggerLaunchRecoveryAttemptParam("t", "r", 0L, "REJECTED", null, null);
    assertThat(param.nextAttemptAt()).isNull();
    assertThat(param.errorCode()).isNull();
  }
}
