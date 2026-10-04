package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwallowedExceptionLoggerTest {

  @Test
  void shouldReturnTypeWhenMessageIsMissing() {
    assertThat(SwallowedExceptionLogger.summary(new IllegalStateException()))
        .isEqualTo("IllegalStateException");
    assertThat(SwallowedExceptionLogger.summary(null)).isEqualTo("(null)");
  }

  @Test
  void shouldKeepSummaryOnOneLineAndRemoveControlCharacters() {
    IllegalArgumentException failure = new IllegalArgumentException("first\r\nsecond\tthird");

    assertThat(SwallowedExceptionLogger.summary(failure))
        .isEqualTo("IllegalArgumentException: first second third");
  }

  @Test
  void shouldMaskCommonCredentials() {
    IllegalStateException failure =
        new IllegalStateException("password=hunter2 token:abc authorization: Bearer auth-token "
            + "{\"api_key\":\"json-key\"} Bearer standalone-token "
            + "http://admin:secret@example.test");

    assertThat(SwallowedExceptionLogger.summary(failure))
        .isEqualTo("IllegalStateException: password=**** token:**** authorization: **** "
            + "{\"api_key\":****} Bearer **** http://****@example.test");
  }

  @Test
  void shouldLimitSummaryLength() {
    IllegalStateException failure = new IllegalStateException("x".repeat(1_024));

    assertThat(SwallowedExceptionLogger.summary(failure))
        .hasSize(SwallowedExceptionLogger.MAX_SUMMARY_LENGTH)
        .endsWith("...");
  }
}
