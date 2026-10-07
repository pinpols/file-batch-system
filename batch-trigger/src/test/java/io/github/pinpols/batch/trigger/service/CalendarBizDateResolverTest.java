package io.github.pinpols.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("日历业务日期解析:按截止时间与节假日滚动规则把触发时刻换算为 bizDate")
class CalendarBizDateResolverTest {

  private final CalendarBizDateResolver resolver =
      new CalendarBizDateResolver(new BatchTimezoneProvider(new BatchTimezoneProperties()));

  @Test
  @DisplayName("截止时间 06:00 之前触发时业务日期回退前一天,02:00 触发落在 03-28")
  void shouldUsePreviousBusinessDayWhenTriggeredBeforeCutoff() {
    LocalDate bizDate = resolver.resolve(
        instant("2026-03-29T02:00:00+08:00"),
        ZoneId.of("Asia/Shanghai"),
        calendar("Asia/Shanghai", "SKIP", Set.of(), Set.of()));

    assertThat(bizDate).isEqualTo(LocalDate.of(2026, Month.MARCH, 28));
  }

  @Test
  @DisplayName("截止时间 06:00 之后触发时业务日期取当天,08:00 触发落在 03-29")
  void shouldUseSameBusinessDayWhenTriggeredAfterCutoff() {
    LocalDate bizDate = resolver.resolve(
        instant("2026-03-29T08:00:00+08:00"),
        ZoneId.of("Asia/Shanghai"),
        calendar("Asia/Shanghai", "SKIP", Set.of(), Set.of()));

    assertThat(bizDate).isEqualTo(LocalDate.of(2026, Month.MARCH, 29));
  }

  @Test
  @DisplayName("回退到的业务日期是节假日且滚动规则为 SKIP 时返回 null,提示调用方跳过调度")
  void shouldSkipWhenPreviousBusinessDayIsHolidayAndRuleIsSkip() {
    LocalDate bizDate = resolver.resolve(
        instant("2026-03-29T02:00:00+08:00"),
        ZoneId.of("Asia/Shanghai"),
        calendar("Asia/Shanghai", "SKIP", Set.of(LocalDate.of(2026, Month.MARCH, 28)), Set.of()));

    assertThat(bizDate).isNull();
  }

  @Test
  @DisplayName("滚动规则为 PREV_WORKDAY 时从节假日业务日期向前搜索最近工作日,03-28 落到 03-27")
  void shouldMoveToPreviousWorkdayWhenHolidayRuleRequiresIt() {
    LocalDate bizDate = resolver.resolve(
        instant("2026-03-29T02:00:00+08:00"),
        ZoneId.of("Asia/Shanghai"),
        calendar(
            "Asia/Shanghai",
            "PREV_WORKDAY",
            Set.of(LocalDate.of(2026, Month.MARCH, 28)),
            Set.of()));

    assertThat(bizDate).isEqualTo(LocalDate.of(2026, Month.MARCH, 27));
  }

  @Test
  @DisplayName("无日历配置时不做截止时间回退,直接取触发时刻在备用时区的本地日期 03-28")
  void shouldFallBackToOriginalTimezoneBasedLogicWhenCalendarIsMissing() {
    LocalDate bizDate =
        resolver.resolve(instant("2026-03-27T16:30:00Z"), ZoneId.of("Asia/Shanghai"), null);

    assertThat(bizDate).isEqualTo(LocalDate.of(2026, Month.MARCH, 28));
  }

  private CalendarBizDateDefinition calendar(
      String timezone,
      String holidayRollRule,
      Set<LocalDate> holidays,
      Set<LocalDate> workdayOverrides) {
    return new CalendarBizDateDefinition(
        timezone, LocalTime.of(6, 0), holidayRollRule, holidays, workdayOverrides);
  }

  private Instant instant(String value) {
    return OffsetDateTime.parse(value).toInstant();
  }
}
