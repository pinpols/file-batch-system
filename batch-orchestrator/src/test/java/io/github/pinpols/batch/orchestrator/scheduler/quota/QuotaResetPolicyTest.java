package io.github.pinpols.batch.orchestrator.scheduler.quota;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.scheduling.QuotaResetPolicy;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配额重置策略,验证配置取值解析,运行时托管判定与日历日起点计算")
class QuotaResetPolicyTest {

  // ── from() ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("重置策略取值为空时回退为不重置")
  void shouldReturnNoneWhenValueIsNull() {
    assertThat(QuotaResetPolicy.from(null)).isEqualTo(QuotaResetPolicy.NONE);
  }

  @Test
  @DisplayName("重置策略取值仅含空白字符时回退为不重置")
  void shouldReturnNoneWhenValueIsBlank() {
    assertThat(QuotaResetPolicy.from("  ")).isEqualTo(QuotaResetPolicy.NONE);
  }

  @Test
  @DisplayName("重置策略取值无法识别时回退为不重置")
  void shouldReturnNoneWhenValueIsUnknown() {
    assertThat(QuotaResetPolicy.from("UNKNOWN_POLICY")).isEqualTo(QuotaResetPolicy.NONE);
  }

  @Test
  @DisplayName("日历日重置策略取值忽略大小写差异均可解析")
  void shouldParseCasedCalendarDay() {
    assertThat(QuotaResetPolicy.from("calendar_day")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
    assertThat(QuotaResetPolicy.from("CALENDAR_DAY")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
    assertThat(QuotaResetPolicy.from("Calendar_Day")).isEqualTo(QuotaResetPolicy.CALENDAR_DAY);
  }

  @Test
  @DisplayName("滑动窗口重置策略取值忽略大小写差异均可解析")
  void shouldParseSlidingWindow() {
    assertThat(QuotaResetPolicy.from("SLIDING_WINDOW")).isEqualTo(QuotaResetPolicy.SLIDING_WINDOW);
    assertThat(QuotaResetPolicy.from("sliding_window")).isEqualTo(QuotaResetPolicy.SLIDING_WINDOW);
  }

  @Test
  @DisplayName("显式声明不重置时按原义解析")
  void shouldParseNoneExplicitly() {
    assertThat(QuotaResetPolicy.from("NONE")).isEqualTo(QuotaResetPolicy.NONE);
    assertThat(QuotaResetPolicy.from("none")).isEqualTo(QuotaResetPolicy.NONE);
  }

  // ── isRuntimeManaged() ────────────────────────────────────────────────────

  @Test
  @DisplayName("不重置策略不参与运行时托管")
  void shouldReportNoneAsNotRuntimeManaged() {
    assertThat(QuotaResetPolicy.NONE.isRuntimeManaged()).isFalse();
  }

  @Test
  @DisplayName("日历日重置策略由运行时托管,按自然日滚动")
  void shouldReportCalendarDayAsRuntimeManaged() {
    assertThat(QuotaResetPolicy.CALENDAR_DAY.isRuntimeManaged()).isTrue();
  }

  @Test
  @DisplayName("滑动窗口重置策略由运行时托管,按滚动窗口计算")
  void shouldReportSlidingWindowAsRuntimeManaged() {
    assertThat(QuotaResetPolicy.SLIDING_WINDOW.isRuntimeManaged()).isTrue();
  }

  // ── startOfCalendarDay() ──────────────────────────────────────────────────

  @Test
  @DisplayName("以正午时刻输入时返回当日零点,年月日与输入保持一致")
  void shouldReturnMidnightOfSameDay() {
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    ZonedDateTime noon = ZonedDateTime.of(2026, 3, 22, 12, 30, 0, 0, zone);

    ZonedDateTime startOfDay = QuotaResetPolicy.startOfCalendarDay(noon);

    assertThat(startOfDay.getHour()).isZero();
    assertThat(startOfDay.getMinute()).isZero();
    assertThat(startOfDay.getSecond()).isZero();
    assertThat(startOfDay.getYear()).isEqualTo(2026);
    assertThat(startOfDay.getMonthValue()).isEqualTo(3);
    assertThat(startOfDay.getDayOfMonth()).isEqualTo(22);
  }

  @Test
  @DisplayName("计算日历日起点时保留输入时刻所在时区")
  void shouldPreserveZone_whenComputingDayStart() {
    ZoneId zone = ZoneId.of("America/New_York");
    ZonedDateTime dt = ZonedDateTime.of(2026, 6, 15, 18, 45, 0, 0, zone);

    ZonedDateTime result = QuotaResetPolicy.startOfCalendarDay(dt);

    assertThat(result.getZone()).isEqualTo(zone);
  }
}
