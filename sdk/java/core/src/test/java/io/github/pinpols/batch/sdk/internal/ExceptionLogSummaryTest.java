package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExceptionLogSummaryTest {

  @Test
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
