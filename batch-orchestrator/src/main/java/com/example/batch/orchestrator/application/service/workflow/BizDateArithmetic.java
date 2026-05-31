package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.utils.Texts;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * ADR-018 跨批量日 bizDate 算术工具。
 *
 * <p>覆盖 {@link com.example.batch.orchestrator.domain.workflow.CrossDayDependencySpec} 的两类计算：
 *
 * <ul>
 *   <li><b>offset 单点</b>：T-N（int），或 {@code MONTH_START} / {@code MONTH_END} / {@code QUARTER_START}
 *       / {@code QUARTER_END} 等命名常量（基于自然月 / 自然季度，不感知节假日）；
 *   <li><b>range 多点</b>：{@code PREV_N_BIZ_DAYS}（按 ISO 周一~周五）/ {@code MTD_TO_YESTERDAY} / {@code
 *       LAST_4_WEEKS}。
 * </ul>
 *
 * <p>当前实现按"工作日 = 周一至周五"近似处理；节假日感知（business_calendar.holiday_roll_rule）留给后续 iteration —— 调用方需要时再扩展。
 */
@Service
public class BizDateArithmetic {

  private static final int RANGE_MAX_DAYS = 90;

  /**
   * 解析 offset：
   *
   * <ul>
   *   <li>int → 自然日加减；
   *   <li>枚举字符串 → MONTH_START / MONTH_END / QUARTER_START / QUARTER_END / YEAR_START / YEAR_END。
   * </ul>
   *
   * 任一参数非法或 null 返回 null。
   */
  public LocalDate resolveOffset(LocalDate bizDate, Integer offsetDays) {
    if (bizDate == null || offsetDays == null) {
      return null;
    }
    return bizDate.plusDays(offsetDays);
  }

  /** 解析命名 offset tag。 */
  public LocalDate resolveNamedOffset(LocalDate bizDate, String namedOffset) {
    if (bizDate == null || !Texts.hasText(namedOffset)) {
      return null;
    }
    String tag = namedOffset.trim().toUpperCase(Locale.ROOT);
    return switch (tag) {
      case "MONTH_START" -> bizDate.withDayOfMonth(1);
      case "MONTH_END" -> bizDate.with(YearMonth.from(bizDate).atEndOfMonth());
      case "QUARTER_START" -> {
        int month = ((bizDate.getMonthValue() - 1) / 3) * 3 + 1;
        yield LocalDate.of(bizDate.getYear(), month, 1);
      }
      case "QUARTER_END" -> {
        int startMonth = ((bizDate.getMonthValue() - 1) / 3) * 3 + 1;
        YearMonth quarterEnd = YearMonth.of(bizDate.getYear(), startMonth + 2);
        yield quarterEnd.atEndOfMonth();
      }
      case "YEAR_START" -> LocalDate.of(bizDate.getYear(), 1, 1);
      case "YEAR_END" -> LocalDate.of(bizDate.getYear(), 12, 31);
      case "PREV_BIZ_DAY" -> previousBusinessDay(bizDate);
      default -> null;
    };
  }

  /**
   * 解析 range tag 为 LocalDate 列表（升序）。
   *
   * <ul>
   *   <li>{@code PREV_N_BIZ_DAYS}（N ≤ {@value #RANGE_MAX_DAYS}）→ 从 bizDate 往回数 N 个工作日（不含 bizDate）；
   *   <li>{@code MTD_TO_YESTERDAY} → 当月 1 号到 bizDate-1 的所有自然日；
   *   <li>{@code LAST_N_WEEKS}（N ≤ 13）→ N 周自然日。
   * </ul>
   *
   * 不识别的 tag 返回空列表。
   */
  public List<LocalDate> resolveRange(LocalDate bizDate, String rangeTag) {
    if (bizDate == null || !Texts.hasText(rangeTag)) {
      return Collections.emptyList();
    }
    String tag = rangeTag.trim().toUpperCase(Locale.ROOT);

    if (tag.startsWith("PREV_") && tag.endsWith("_BIZ_DAYS")) {
      int n = parseLeadingInt(tag.substring("PREV_".length()));
      if (n <= 0 || n > RANGE_MAX_DAYS) {
        return Collections.emptyList();
      }
      return previousBusinessDays(bizDate, n);
    }
    if ("MTD_TO_YESTERDAY".equals(tag)) {
      LocalDate start = bizDate.withDayOfMonth(1);
      LocalDate end = bizDate.minusDays(1);
      return inclusiveRange(start, end);
    }
    if (tag.startsWith("LAST_") && tag.endsWith("_WEEKS")) {
      int weeks = parseLeadingInt(tag.substring("LAST_".length()));
      if (weeks <= 0 || weeks > 13) {
        return Collections.emptyList();
      }
      LocalDate end = bizDate.minusDays(1);
      LocalDate start = end.minusWeeks(weeks).plusDays(1);
      return inclusiveRange(start, end);
    }
    return Collections.emptyList();
  }

  /** 前一个工作日(周一~周五;暂不感知节假日)。 */
  public LocalDate previousBusinessDay(LocalDate bizDate) {
    if (bizDate == null) {
      return null;
    }
    LocalDate prev = bizDate.minusDays(1);
    while (isWeekend(prev)) {
      prev = prev.minusDays(1);
    }
    return prev;
  }

  /** 后一个工作日(周一~周五;暂不感知节假日)。 */
  public LocalDate nextBusinessDay(LocalDate bizDate) {
    if (bizDate == null) {
      return null;
    }
    LocalDate next = bizDate.plusDays(1);
    while (isWeekend(next)) {
      next = next.plusDays(1);
    }
    return next;
  }

  private List<LocalDate> previousBusinessDays(LocalDate bizDate, int count) {
    List<LocalDate> dates = new ArrayList<>();
    LocalDate cursor = bizDate.minusDays(1);
    while (dates.size() < count) {
      if (!isWeekend(cursor)) {
        dates.add(cursor);
      }
      cursor = cursor.minusDays(1);
      // 兜底防御：避免极端配置死循环（理论上 count ≤ RANGE_MAX_DAYS，循环会停）
      if (cursor.isBefore(bizDate.minusYears(1))) {
        break;
      }
    }
    Collections.reverse(dates);
    return dates;
  }

  /** 是否周末(周六/周日);当前节假日语义即周末近似。 */
  public boolean isWeekend(LocalDate date) {
    DayOfWeek dow = date.getDayOfWeek();
    return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
  }

  private List<LocalDate> inclusiveRange(LocalDate start, LocalDate end) {
    if (start == null || end == null || start.isAfter(end)) {
      return Collections.emptyList();
    }
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
      dates.add(cursor);
      if (dates.size() > RANGE_MAX_DAYS) {
        break;
      }
    }
    return dates;
  }

  private int parseLeadingInt(String text) {
    StringBuilder digits = new StringBuilder();
    for (char c : text.toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      } else {
        break;
      }
    }
    if (digits.isEmpty()) {
      return -1;
    }
    try {
      return Integer.parseInt(digits.toString());
    } catch (NumberFormatException nfe) {
      return -1;
    }
  }
}
