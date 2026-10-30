package io.github.pinpols.batch.console.shared.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("控制台查询时间解析: 按日截止时刻与显式瞬间处理")
class ConsoleQuerySupportTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"id\":null}",
        "{\"id\":true}",
        "{\"id\":\"not-a-number\"}",
        "[]",
        "not-json"
      })
  @DisplayName("非空损坏游标不能静默回退到首页")
  void shouldRejectMalformedCursorPayloads(String payload) {
    String cursor = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> ConsoleQuerySupport.decodeCursorId(cursor))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("空游标表示首页，非法 Base64 不可作为首页")
  void shouldDistinguishFirstPageFromInvalidEncoding() {
    assertThat(ConsoleQuerySupport.decodeCursorId(null)).isNull();
    assertThat(ConsoleQuerySupport.decodeCursorId("  ")).isNull();
    assertThatThrownBy(() -> ConsoleQuerySupport.decodeCursorId("%%%"))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.5",
        "1.0",
        "1e0",
        "9007199254740992.1",
        "9223372036854775808",
        "-9223372036854775809"
      })
  @DisplayName("非整数或超出 long 范围的游标 ID 必须拒绝")
  void shouldRejectFractionalOrOutOfRangeNumericCursor(String number) {
    String json = "{\"id\":" + number + "}";
    String cursor = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> ConsoleQuerySupport.decodeCursorId(cursor))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "42", "9223372036854775807", "-9223372036854775808", "\"42\""})
  @DisplayName("有效整数游标和既有整数文本保留精确值")
  void shouldPreserveValidIntegralCursorValues(String encodedNumber) {
    String json = "{\"id\":" + encodedNumber + "}";
    String cursor = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    assertThat(ConsoleQuerySupport.decodeCursorId(cursor))
        .isEqualTo(Long.parseLong(encodedNumber.replace("\"", "")));
  }

  @Test
  @DisplayName("仅给出日期时,当日截止时刻应取数据库精度下的最后一个微秒")
  void shouldUseLastDatabaseMicrosecond_whenDateOnlyEndOfDay() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-09-27", "toTime", ZoneId.of("Asia/Shanghai"));

    assertThat(end).isEqualTo(Instant.parse("2026-09-27T15:59:59.999999Z"));
  }

  @Test
  @DisplayName("日期跨越夏令时切换时,截止时刻应按次日零点换算为同一瞬间")
  void shouldUseNextLocalMidnight_whenDayCrossesDstTransition() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-03-08", "toTime", ZoneId.of("America/New_York"));

    assertThat(end).isEqualTo(Instant.parse("2026-03-09T03:59:59.999999Z"));
  }

  @Test
  @DisplayName("传入带微秒的显式瞬间时,截止时刻应原样返回")
  void shouldRemainUnchanged_whenExplicitInstantGiven() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-09-27T12:34:56.123456Z", "toTime", ZoneId.of("Asia/Shanghai"));

    assertThat(end).isEqualTo(Instant.parse("2026-09-27T12:34:56.123456Z"));
  }
}
