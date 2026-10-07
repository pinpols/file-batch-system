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
import java.time.Month;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批量日时间策略解析器;验证夏令时缺口与重叠时刻的截断换算,立即失败的策略分支以及策略快照")
class BatchDayTimePolicyResolverTest {

  private final BatchDayTimePolicyResolver resolver = new BatchDayTimePolicyResolver(
      new BatchTimezoneProvider(new BatchTimezoneProperties()), new CutoffScheduleResolver());

  @Test
  @DisplayName("默认缺口策略下把不存在的本地时刻顺延到下一个有效时刻,且策略快照同步反映缺口与重叠策略")
  void shouldMoveGapCutoffToNextValidInstantByDefault() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(2, 30), "RUN_AT_NEXT_VALID_TIME", null);

    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, Month.MARCH, 7));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    // R4-P1-5 后 DEFAULT_OVERLAP_POLICY 改成 RUN_ONCE_LATER_OFFSET（保护未配置 tenant 不被早 1h 触发误判
    // late arrival）；test snapshot 同步对齐。
    assertThat(resolver.snapshot(calendar))
        .isEqualTo("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_LATER_OFFSET");
  }

  @Test
  @DisplayName("缺口策略要求立即失败时对不存在的本地时刻抛出业务异常")
  void shouldFailFastWhenGapPolicyRejectsInvalidLocalTime() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(2, 30), "FAIL_FAST", null);

    assertThatThrownBy(() -> resolver.resolveCutoffAt(calendar, LocalDate.of(2026, Month.MARCH, 7)))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("重叠策略要求取较晚偏移时按标准时偏移换算截断时刻")
  void shouldUseLaterOffsetWhenOverlapPolicyRequiresIt() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(1, 30), null, "RUN_ONCE_LATER_OFFSET");

    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, Month.OCTOBER, 31));

    assertThat(cutoffAt).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
  }

  @Test
  @DisplayName("策略不支持用于截断换算时降级到默认重叠策略,换算结果与策略快照保持一致")
  void shouldDegradeRunTwiceToEarlierOffsetForCutoffAndReflectInSnapshot() {
    BusinessCalendarEntity calendar =
        calendar("America/New_York", LocalTime.of(1, 30), null, "RUN_TWICE");

    // RUN_TWICE 不支持用于 cutoff, 必须降级到 DEFAULT_OVERLAP_POLICY；R4-P1-5 后默认是 RUN_ONCE_LATER_OFFSET，
    // 对应秋令 overlap 选标准时 offset → 2026-11-01 01:30 本地（标准时 -05:00）= 06:30Z。
    Instant cutoffAt = resolver.resolveCutoffAt(calendar, LocalDate.of(2026, Month.OCTOBER, 31));

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
