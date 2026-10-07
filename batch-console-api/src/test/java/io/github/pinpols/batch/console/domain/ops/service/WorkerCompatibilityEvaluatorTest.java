package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.ReasonCode;
import io.github.pinpols.batch.console.domain.ops.dto.WorkerCompatibility.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** SDK 运行时可见性 ①:验证按 sdkVersion 对照平台当前 SDK 主版本算兼容。 */
@DisplayName("Worker 兼容性评估:按上报版本与平台主版本比较, 判定兼容, 过期, 不支持与未知")
class WorkerCompatibilityEvaluatorTest {

  private final WorkerCompatibilityEvaluator evaluator = new WorkerCompatibilityEvaluator();

  @Test
  @DisplayName("主版本相同:判定兼容, 并回填上报版本与平台主版本")
  void shouldReportOk_whenMajorMatchesPlatform() {
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
  @DisplayName("主版本落后:判定 SDK 过期, 原因码为版本落后")
  void shouldReportOutdated_whenMajorBelowPlatform() {
    WorkerCompatibility result = evaluator.evaluate("0.9.3");

    assertThat(result.status()).isEqualTo(Status.SDK_OUTDATED);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_BEHIND);
  }

  @Test
  @DisplayName("主版本超前:判定协议不支持, 原因码为版本超前")
  void shouldReportUnsupported_whenMajorAbovePlatform() {
    WorkerCompatibility result = evaluator.evaluate("2.0.0-rc");

    assertThat(result.status()).isEqualTo(Status.PROTOCOL_UNSUPPORTED);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_AHEAD);
  }

  @ParameterizedTest
  @DisplayName("无法解析:空值, 空白与非法格式一律判定未知, 并保留原始上报值")
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "snapshot", "vX", "-1.0"})
  void shouldReportUnknown_whenSdkVersionUnparseable(String reported) {
    WorkerCompatibility result = evaluator.evaluate(reported);

    assertThat(result.status()).isEqualTo(Status.UNKNOWN);
    assertThat(result.reasonCode()).isEqualTo(ReasonCode.SDK_VERSION_UNKNOWN);
    assertThat(result.reportedSdkVersion()).isEqualTo(reported);
  }

  @ParameterizedTest
  @DisplayName("格式兼容:带前缀或快照后缀的主版本 1 形式均判定兼容")
  @ValueSource(strings = {"1.0.0", "v1.2.3", "1", "1.999.0", "1.0.0-SNAPSHOT"})
  void shouldReportOk_whenMajorOneFormatsVary(String reported) {
    assertThat(evaluator.evaluate(reported).status()).isEqualTo(Status.OK);
  }
}
