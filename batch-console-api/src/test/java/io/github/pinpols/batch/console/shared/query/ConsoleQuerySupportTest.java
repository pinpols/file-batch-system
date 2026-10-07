package io.github.pinpols.batch.console.shared.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("控制台查询时间解析: 按日截止时刻与显式瞬间处理")
class ConsoleQuerySupportTest {

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
