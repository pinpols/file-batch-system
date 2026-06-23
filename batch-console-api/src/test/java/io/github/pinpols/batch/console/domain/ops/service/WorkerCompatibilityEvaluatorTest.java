package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.ReasonCode;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SDK 运行时可见性 ①:验证按 sdkVersion 对照平台当前 SDK 主版本算兼容。 */
class WorkerCompatibilityEvaluatorTest {

  private final WorkerCompatibilityEvaluator evaluator = new WorkerCompatibilityEvaluator();

  @Test
  void okWhenSameMajor() {
    // arrange
    String reported = "1.4.0";

    // act
    WorkerCompatibility result = evaluator.evaluate(reported);

    // assert
    assertThat(result.status()).isEqualTo(Status.OK);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.COMPATIBLE);
    assertThat(result.reportedSdkVersion()).isEqualTo("1.4.0");
    assertThat(result.platformSdkMajor()).isEqualTo("v1");
  }

  @Test
  void sdkOutdatedWhenMajorBelowPlatform() {
    WorkerCompatibility result = evaluator.evaluate("0.9.3");

    assertThat(result.status()).isEqualTo(Status.SDK_OUTDATED);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_BEHIND);
  }

  @Test
  void protocolUnsupportedWhenMajorAbovePlatform() {
    WorkerCompatibility result = evaluator.evaluate("2.0.0-rc");

    assertThat(result.status()).isEqualTo(Status.PROTOCOL_UNSUPPORTED);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_AHEAD);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "snapshot", "vX", "-1.0"})
  void unknownWhenUnparseable(String reported) {
    WorkerCompatibility result = evaluator.evaluate(reported);

    assertThat(result.status()).isEqualTo(Status.UNKNOWN);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_UNKNOWN);
    assertThat(result.reportedSdkVersion()).isEqualTo(reported);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.0.0", "v1.2.3", "1", "1.999.0", "1.0.0-SNAPSHOT"})
  void okForVariousMajorOneFormats(String reported) {
    assertThat(evaluator.evaluate(reported).status()).isEqualTo(Status.OK);
  }
}
