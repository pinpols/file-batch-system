package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class BatchDayTimePolicyResolverTest {

  private final BatchDayTimePolicyResolver resolver =
      new BatchDayTimePolicyResolver(new BatchTimezoneProvider(new BatchTimezoneProperties()));

  @Test
  void shouldMoveGapCutoffToNextValidInstantByDefault() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(2, 30), "RUN_AT_NEXT_VALID_TIME", null);

    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 3, 7));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    assertThat(resolver.snapshot(calendar))
        .isEqualTo("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET");
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

    // RUN_TWICE 不支持用于 cutoff, 必须降级到 RUN_ONCE_EARLIER_OFFSET
    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, 10, 31));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    assertThat(resolver.snapshot(calendar))
        .isEqualTo("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET");
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
