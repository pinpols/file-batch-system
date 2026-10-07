package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExceptionLogSummary — 异常消息脱敏与长度截断")
class ExceptionLogSummaryTest {

  @Test
  @DisplayName("摘要中密码、授权头与令牌被脱敏,换行被清理且超长截断")
  void shouldSanitizeMaskAndLimitExceptionMessage() {
    IllegalStateException failure = new IllegalStateException(
        "password=hunter2\r\nauthorization: Bearer abc {\"token\":\"secret\"} "
            + "x".repeat(1_024));

    assertThat(ExceptionLogSummary.of(failure))
        .doesNotContain("hunter2", "abc", "secret", "\r", "\n")
        .contains("password=****", "authorization: ****", "\"token\":****")
        .hasSize(ExceptionLogSummary.MAX_LENGTH)
        .endsWith("...");
  }
}
