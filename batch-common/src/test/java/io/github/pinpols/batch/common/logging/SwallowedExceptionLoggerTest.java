package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("被吞异常摘要:缺失消息处理,控制字符清理,凭据脱敏与长度上限")
class SwallowedExceptionLoggerTest {

  @Test
  @DisplayName("异常无消息时只给类型名,输入为空时给固定占位")
  void shouldReturnTypeWhenMessageIsMissing() {
    assertThat(SwallowedExceptionLogger.summary(new IllegalStateException()))
        .isEqualTo("IllegalStateException");
    assertThat(SwallowedExceptionLogger.summary(null)).isEqualTo("(null)");
  }

  @Test
  @DisplayName("消息中的换行与制表符被替换为空格,摘要保持单行")
  void shouldKeepSummaryOnOneLineAndRemoveControlCharacters() {
    IllegalArgumentException failure = new IllegalArgumentException("first\r\nsecond\tthird");

    assertThat(SwallowedExceptionLogger.summary(failure))
        .isEqualTo("IllegalArgumentException: first second third");
  }

  @Test
  @DisplayName("常见凭据字段(口令、令牌、接口密钥、地址内嵌账号口令与 Bearer 头)全部被掩码")
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
  @DisplayName("超长消息被截断到长度上限并以省略号结尾")
  void shouldLimitSummaryLength() {
    IllegalStateException failure = new IllegalStateException("x".repeat(1_024));

    assertThat(SwallowedExceptionLogger.summary(failure))
        .hasSize(SwallowedExceptionLogger.MAX_SUMMARY_LENGTH)
        .endsWith("...");
  }
}
