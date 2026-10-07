package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("营业日算术: 偏移量解析, 命名档位与区间解析口径")
class BizDateArithmeticTest {

  private BizDateArithmetic arithmetic;

  @BeforeEach
  void setUp() {
    arithmetic = new BizDateArithmetic();
  }

  // ── offset ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("按负一天偏移解析出前一日")
  void shouldResolvePreviousDay_whenOffsetIsMinusOne() {
    assertThat(arithmetic.resolveOffset(LocalDate.of(2026, Month.MAY, 4), -1))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  @DisplayName("按正数偏移解析出之后的第七天")
  void shouldResolveLaterDay_whenOffsetIsPositive() {
    assertThat(arithmetic.resolveOffset(LocalDate.of(2026, Month.MAY, 4), 7))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 11));
  }

  @Test
  @DisplayName("基准日或偏移量为空时返回空")
  void shouldReturnNull_whenOffsetInputsMissing() {
    assertThat(arithmetic.resolveOffset(null, -1)).isNull();
    assertThat(arithmetic.resolveOffset(LocalDate.now(), null)).isNull();
  }

  // ── named offset ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("命名档位解析月首时返回当月第一天")
  void shouldReturnFirstDay_whenResolvingMonthStart() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "MONTH_START"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 1));
  }

  @Test
  @DisplayName("命名档位解析月末时返回当月最后一天, 平年二月为二十八日")
  void shouldReturnLastDay_whenResolvingMonthEnd() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "MONTH_END"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 31));
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.FEBRUARY, 4), "MONTH_END"))
        .isEqualTo(LocalDate.of(2026, Month.FEBRUARY, 28));
  }

  @Test
  @DisplayName("命名档位解析季初时返回所在季度第一天, 跨季也成立")
  void shouldReturnFirstDay_whenResolvingQuarterStart() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "QUARTER_START"))
        .isEqualTo(LocalDate.of(2026, Month.APRIL, 1));
    assertThat(
            arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.JANUARY, 15), "QUARTER_START"))
        .isEqualTo(LocalDate.of(2026, Month.JANUARY, 1));
  }

  @Test
  @DisplayName("命名档位解析季末时返回所在季度最后一天")
  void shouldReturnLastDay_whenResolvingQuarterEnd() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "QUARTER_END"))
        .isEqualTo(LocalDate.of(2026, Month.JUNE, 30));
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.NOVEMBER, 4), "QUARTER_END"))
        .isEqualTo(LocalDate.of(2026, Month.DECEMBER, 31));
  }

  @Test
  @DisplayName("解析前一营业日时跳过周末, 周一回退到上周五")
  void shouldSkipWeekend_whenResolvingPreviousBizDay() {
    // 2026-05-04 is Monday → prev biz = Friday 2026-05-01
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 4), "PREV_BIZ_DAY"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 1));
    // Tuesday → prev biz = Monday
    assertThat(arithmetic.resolveNamedOffset(LocalDate.of(2026, Month.MAY, 5), "PREV_BIZ_DAY"))
        .isEqualTo(LocalDate.of(2026, Month.MAY, 4));
  }

  @Test
  @DisplayName("命名档位无法识别时返回空")
  void shouldReturnNull_whenNamedOffsetUnknown() {
    assertThat(arithmetic.resolveNamedOffset(LocalDate.now(), "WHATEVER")).isNull();
  }

  // ── range ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("解析前五个营业日时跳过周末, 按时间顺序给出五天")
  void shouldSkipWeekends_whenResolvingPreviousFiveBizDays() {
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
  @DisplayName("解析月初至昨日区间时覆盖本月已过自然日, 不含当日")
  void shouldCoverMonthStartToYesterday_whenResolvingMtd() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 4), "MTD_TO_YESTERDAY");
    assertThat(dates).hasSize(3);
    assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, Month.MAY, 1));
    assertThat(dates.get(2)).isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  @DisplayName("当月第一天解析月初至昨日区间时返回空")
  void shouldReturnEmptyRange_whenMtdOnFirstOfMonth() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 1), "MTD_TO_YESTERDAY");
    assertThat(dates).isEmpty();
  }

  @Test
  @DisplayName("解析最近两周时返回连续十四个自然日, 结束于前一日")
  void shouldReturnFourteenNaturalDays_whenResolvingLastTwoWeeks() {
    List<LocalDate> dates =
        arithmetic.resolveRange(LocalDate.of(2026, Month.MAY, 4), "LAST_2_WEEKS");
    assertThat(dates).hasSize(14);
    assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, Month.APRIL, 20));
    assertThat(dates.get(13)).isEqualTo(LocalDate.of(2026, Month.MAY, 3));
  }

  @Test
  @DisplayName("区间档位无法识别时返回空")
  void shouldReturnEmpty_whenRangeTagUnknown() {
    assertThat(arithmetic.resolveRange(LocalDate.now(), "GARBAGE")).isEmpty();
  }

  @Test
  @DisplayName("区间要求的营业日数量超过上限时返回空")
  void shouldReturnEmpty_whenBizDayCountExceedsCap() {
    assertThat(arithmetic.resolveRange(LocalDate.now(), "PREV_999_BIZ_DAYS")).isEmpty();
  }

  @Test
  @DisplayName("基准日或档位为空时区间解析返回空")
  void shouldReturnEmpty_whenRangeInputsMissing() {
    assertThat(arithmetic.resolveRange(null, "PREV_5_BIZ_DAYS")).isEmpty();
    assertThat(arithmetic.resolveRange(LocalDate.now(), null)).isEmpty();
  }
}
