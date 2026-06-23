package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class BatchDayTimePolicyResolverTest {

  private final BatchDayTimePolicyResolver resolver =
      new BatchDayTimePolicyResolver(
          new BatchTimezoneProvider(new BatchTimezoneProperties()), new CutoffScheduleResolver());

  @Test
  void shouldMoveGapCutoffToNextValidInstantByDefault() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(2, 30), "RUN_AT_NEXT_VALID_TIME", null);

    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 3, 7));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    // R4-P1-5 后 DEFAULT_OVERLAP_POLICY 改成 RUN_ONCE_LATER_OFFSET（保护未配置 tenant 不被早 1h 触发误判
    // late arrival）；test snapshot 同步对齐。
    assertThat(resolver.snapshot(calendar))
        .isEqualTo("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_LATER_OFFSET");
  }

  @Test
  void shouldFailFastWhenGapPolicyRejectsInvalidLocalTime() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(2, 30), "FAIL_FAST", null);

    assertThatThrownBy(() -> resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 3, 7)))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldUseLaterOffsetWhenOverlapPolicyRequiresIt() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(1, 30), null, "RUN_ONCE_LATER_OFFSET");

    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 10, 31));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
  }

  @Test
  void shouldDegradeRunTwiceToEarlierOffsetForCutoffAndReflectInSnapshot() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(1, 30), null, "RUN_TWICE");

    // RUN_TWICE 不支持用于 cutoff, 必须降级到 DEFAULT_OVERLAP_POLICY；R4-P1-5 后默认是 RUN_ONCE_LATER_OFFSET，
    // 对应秋令 overlap 选标准时 offset → 2026-11-01 01:30 本地（标准时 -05:00）= 06:30Z。
    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 10, 31));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
    assertThat(resolver.snapshot(calendar))
        .isEqualTo("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_LATER_OFFSET");
  }

  private BusinessCalendarEntity calendar(
      String timezone, LocalTime cutoffTime, String gapPolicy, String overlapPolicy) {
    return new BusinessCalendarEntity(
        1L,
        "t1",
        "CAL",
        "Calendar",
        timezone,
        "SKIP",
        "NONE",
        0,
        cutoffTime,
        60,
        120,
        "ALLOW_OVERLAP",
        gapPolicy,
        overlapPolicy,
        true);
  }
}
