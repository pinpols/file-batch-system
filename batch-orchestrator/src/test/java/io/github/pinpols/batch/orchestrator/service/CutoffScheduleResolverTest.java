package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("截止时间解析器: 计划默认值与日期覆盖的优先级及非法配置回退口径")
class CutoffScheduleResolverTest {

  private CutoffScheduleResolver resolver;
  private final LocalTime baseDefault = LocalTime.of(6, 0);

  @BeforeEach
  void setUp() {
    resolver = new CutoffScheduleResolver();
  }

  @Test
  @DisplayName("未配置截止时间计划时返回调用方传入的基础默认值")
  void shouldReturnBaseDefault_whenScheduleIsNull() {
    assertThat(resolver.resolveCutoffTime(null, LocalDate.of(2026, Month.MAY, 4), baseDefault))
        .isEqualTo(baseDefault);
  }

  @Test
  @DisplayName("计划配置不是合法结构时忽略该配置并回退基础默认值")
  void shouldReturnBaseDefault_whenScheduleIsInvalid() {
    assertThat(
            resolver.resolveCutoffTime("not-json{[", LocalDate.of(2026, Month.MAY, 4), baseDefault))
        .isEqualTo(baseDefault);
  }

  @Test
  @DisplayName("无任何日期覆盖命中时采用计划内的默认截止时间")
  void shouldUseScheduleDefault_whenNoOverrideMatches() {
    String spec = "{\"default\":\"05:30\",\"overrides\":[]}";
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.MAY, 4), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
  }

  @Test
  @DisplayName("指定日期覆盖优先于计划默认值,同日之外仍回到计划默认值")
  void shouldPreferExactDateOverride_whenDateMatches() {
    String spec = "{\"default\":\"05:30\",\"overrides\":["
        + "{\"date\":\"2026-12-24\",\"cutoff\":\"13:00\",\"reason\":\"圣诞夜半天班\"}]}";
    assertThat(
            resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.DECEMBER, 24), baseDefault))
        .isEqualTo(LocalTime.of(13, 0));
    // 同 schedule 其它日期回到 default
    assertThat(
            resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.DECEMBER, 25), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
  }

  @Test
  @DisplayName("星期模式覆盖只在生效区间内命中,超出结束日期后回到计划默认值")
  void shouldApplyWeekdayOverride_whenDateInRange() {
    String spec = "{\"default\":\"06:00\",\"overrides\":["
        + "{\"weekdayPattern\":\"FRIDAY\",\"cutoff\":\"05:30\","
        + "\"from\":\"2026-06-01\",\"to\":\"2026-08-31\"}]}";
    // 2026-06-12 is Friday in window
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.JUNE, 12), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
    // 2026-09-04 Friday but outside to
    assertThat(
            resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.SEPTEMBER, 4), baseDefault))
        .isEqualTo(LocalTime.of(6, 0));
    // 2026-06-12 if treated as Saturday no match — actually 2026-06-12 is Friday, kept
  }

  @Test
  @DisplayName("同一天既命中星期模式又命中指定日期时以指定日期为准")
  void shouldPreferExactDate_whenWeekdayAlsoMatches() {
    String spec = "{\"default\":\"06:00\",\"overrides\":["
        + "{\"weekdayPattern\":\"FRIDAY\",\"cutoff\":\"05:30\"},"
        + "{\"date\":\"2026-12-25\",\"cutoff\":\"13:00\"}]}";
    // 2026-12-25 is Friday — exact wins
    assertThat(
            resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.DECEMBER, 25), baseDefault))
        .isEqualTo(LocalTime.of(13, 0));
  }

  @Test
  @DisplayName("覆盖条目的日期无法解析时忽略该条目,采用计划默认截止时间")
  void shouldIgnoreOverride_whenDateUnparsable() {
    String spec =
        "{\"default\":\"06:00\",\"overrides\":[{\"date\":\"BROKEN\",\"cutoff\":\"05:00\"}]}";
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.MAY, 4), baseDefault))
        .isEqualTo(LocalTime.of(6, 0));
  }

  @Test
  @DisplayName("计划未配置默认截止时间且无覆盖命中时回退基础默认值")
  void shouldFallBackToBaseDefault_whenScheduleHasNoDefault() {
    String spec = "{\"overrides\":[{\"date\":\"2026-01-01\",\"cutoff\":\"08:00\"}]}";
    // Different date, no default → base
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, Month.MAY, 4), baseDefault))
        .isEqualTo(baseDefault);
  }
}
