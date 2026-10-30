package io.github.pinpols.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.trigger.support.ScheduledFireTimeSource;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@DisplayName("Quartz 计划 fire 枚举器：遵循 Quartz 时区、DST、间隔和查询边界")
class QuartzScheduledFireEnumeratorTest {

  private final QuartzScheduledFireEnumerator enumerator = new QuartzScheduledFireEnumerator();

  @Test
  @DisplayName("CRON 计划由 Quartz 按时区计算，并跳过 DST 春季不存在的本地时刻")
  void shouldUseQuartzCronTimezoneAndDaylightSavingRules() {
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("cron", "test")
        .startAt(Date.from(Instant.parse("2026-03-07T00:00:00Z")))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 30 2 * * ?")
            .inTimeZone(TimeZone.getTimeZone(ZoneId.of("America/New_York"))))
        .build();

    assertThat(enumerator.enumerate(
            trigger,
            Instant.parse("2026-03-07T00:00:00Z"),
            Instant.parse("2026-03-10T00:00:00Z"),
            10))
        .containsExactly(
            Instant.parse("2026-03-07T07:30:00Z"), Instant.parse("2026-03-09T06:30:00Z"));
  }

  @Test
  @DisplayName("FIXED_RATE 枚举严格排除起点并包含终点")
  void shouldEnumerateFixedRateWithinExclusiveInclusiveWindow() {
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("interval", "test")
        .startAt(Date.from(Instant.parse("2026-01-01T00:00:00Z")))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).repeatForever())
        .build();

    assertThat(enumerator.enumerate(
            trigger,
            Instant.parse("2026-01-01T00:01:00Z"),
            Instant.parse("2026-01-01T00:03:00Z"),
            10))
        .containsExactly(
            Instant.parse("2026-01-01T00:02:00Z"), Instant.parse("2026-01-01T00:03:00Z"));
  }

  @Test
  @DisplayName("窗口内 fire 数量超过上限时失败，不静默截断预期计划")
  void shouldFailInsteadOfSilentlyTruncatingOccurrences() {
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("dense", "test")
        .startAt(Date.from(Instant.parse("2026-01-01T00:00:00Z")))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever())
        .build();

    assertThatThrownBy(() -> enumerator.enumerate(
            trigger,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:03Z"),
            2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("occurrence limit");
  }

  @Test
  @DisplayName("项目时间源保持起点排除和终点包含,不要求规划器认识Quartz类型")
  void shouldEnumerateProjectSource_whenScheduleEngineIsAbstracted() {
    Instant first = Instant.parse("2026-01-01T00:01:00Z");
    Instant last = first.plusSeconds(60);
    List<Instant> times = List.of(first, last);
    ScheduledFireTimeSource source =
        after -> times.stream().filter(time -> time.isAfter(after)).findFirst().orElse(null);
    assertThat(enumerator.enumerateFromSource(source, first.minusSeconds(60), last, 2))
        .containsExactly(first, last);
    assertThat(source.nextAfter(last)).isNull();
  }

  @Test
  @DisplayName("时间源未推进时失败,避免物化循环或错误覆盖声明")
  void shouldFail_whenProjectSourceDoesNotAdvance() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    ScheduledFireTimeSource source = after -> after;
    assertThatThrownBy(() -> enumerator.enumerateFromSource(source, now, now.plusSeconds(60), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-advancing");
  }
}
