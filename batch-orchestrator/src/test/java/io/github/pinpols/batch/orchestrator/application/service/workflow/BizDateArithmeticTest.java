package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BizDateArithmeticTest {

  private BizDateArithmetic arithmetic;

  @BeforeEach
  void setUp() {
    arithmetic = new BizDateArithmetic();
  }

  // ── offset ────────────────────────────────────────────────────────────────

  @Test
  void offsetMinusOneDay() {
    assertThat(arithmetic.resolveOffset(LocalDate.of(2026, Month.MAY, 4), -1))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  void offsetPlusSeven() {
    assertThat(arithmetic.resolveOffset(LocalDate.of(2026, Month.MAY, 4), 7))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 11));
  }

  @Test
  void offsetReturnsNullOnNullInputs() {
    assertThat(arithmetic.resolveOffset(null, -1)).isNull();
    assertThat(arithmetic.resolveOffset(LocalDate.now(), null)).isNull();
  }

  // ── named offset ──────────────────────────────────────────────────────────

  @Test
  void monthStartIsFirstOfMonth() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "MONTH_START"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 1));
  }

  @Test
  void monthEndIsLastOfMonth() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "MONTH_END"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 31));
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.FEBRUARY, 4), "MONTH_END"))
        .isEqualTo(LocalDate.of(2026, Month.FEBRUARY, 28));
  }

  @Test
  void quarterStartIsFirstOfQuarter() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "QUARTER_START"))
        .isEqualTo(LocalDate.of(2026, Month.APRIL, 1));
    assertThat(
            arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.JANUARY, 15), "QUARTER_START"))
        .isEqualTo(LocalDate.of(2026, Month.JANUARY, 1));
  }

  @Test
  void quarterEndIsLastOfQuarter() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "QUARTER_END"))
        .isEqualTo(LocalDate.of(2026, Month.JUNE, 30));
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.NOVEMBER, 4), "QUARTER_END"))
        .isEqualTo(LocalDate.of(2026, Month.DECEMBER, 31));
  }

  @Test
  void prevBizDaySkipsWeekend() {
    // 2026-05-04 is Monday → prev biz = Friday 2026-05-01
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "PREV_BIZ_DAY"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 1));
    // Tuesday → prev biz = Monday
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 5), "PREV_BIZ_DAY"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 4));
  }

  @Test
  void unknownNamedOffsetReturnsNull() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.now(), "WHATEVER")).isNull();
  }

  // ── range ─────────────────────────────────────────────────────────────────

  @Test
  void prev5BizDaysSkipsWeekends() {
    // 2026-05-04 is Monday → prev 5 biz days = Apr 27 (Mon), Apr 28 (Tue), Apr 29 (Wed), Apr 30
    // (Thu), May 1 (Fri)
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 4), "PREV_5_BIZ_DAYS");
    assertThat(dates)
        .containsExactly(
            LocalDate.of(2026, Month.APRIL, 27),
            LocalDate.of(2026, Month.APRIL, 28),
            LocalDate.of(2026, Month.APRIL, 29),
            LocalDate.of(2026, Month.APRIL, 30),
            LocalDate.of(2026, Month.MAY, 1));
  }

  @Test
  void mtdToYesterdayCoversMonthStartToBizDateMinusOne() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 4), "MTD_TO_YESTERDAY");
    assertThat(dates).hasSize(3);
    assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, Month.MAY, 1));
    assertThat(dates.get(2)).isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  void mtdToYesterdayOnFirstOfMonthIsEmpty() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 1), "MTD_TO_YESTERDAY");
    assertThat(dates).isEmpty();
  }

  @Test
  void last2WeeksReturns14NaturalDays() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 4), "LAST_2_WEEKS");
    assertThat(dates).hasSize(14);
    assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, Month.APRIL, 20));
    assertThat(dates.get(13)).isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  void rangeRejectsNonsenseTag() {
    assertThat(arithmetic.resolveRange(LocalDate.now(), "GARBAGE")).isEmpty();
  }

  @Test
  void rangeRejectsExcessiveBizDays() {
    assertThat(arithmetic.resolveRange(LocalDate.now(), "PREV_999_BIZ_DAYS")).isEmpty();
  }

  @Test
  void rangeNullInputsReturnEmpty() {
    assertThat(arithmetic.resolveRange(null, "PREV_5_BIZ_DAYS")).isEmpty();
    assertThat(arithmetic.resolveRange(LocalDate.now(), null)).isEmpty();
  }
}
