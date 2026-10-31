package io.github.pinpols.batch.orchestrator.integration;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 月分区迁移用 {@code CURRENT_DATE} 滑动建区。对象数量不变，但表名和边界会在跨月后改变。
 * 清单哈希按上海时区的当前月改写成相对偏移，避免每个自然月都要重写快照。
 */
final class RollingMonthCatalogNormalizer {

  private static final int WINDOW_MONTHS = 40;
  private static final Pattern MONTH_BOUNDARY = Pattern.compile(
      "(?<!\\d)(\\d{4})-(\\d{2})-01(?: 00:00:00(?:\\.0+)?(?:\\+08(?::00)?|\\+0800))?");
  private static final Pattern YEAR_MONTH_UNDERSCORE =
      Pattern.compile("(?<!\\d)(\\d{4})_(\\d{2})(?!\\d)");
  private static final Pattern YEAR_MONTH_COMPACT =
      Pattern.compile("(?<!\\d)(\\d{4})(\\d{2})(?!\\d)");

  private RollingMonthCatalogNormalizer() {}

  static String normalize(String canonical, LocalDate catalogDate) {
    YearMonth currentMonth = YearMonth.from(catalogDate);
    String normalized = replaceMonthTokens(canonical, MONTH_BOUNDARY, currentMonth);
    normalized = replaceMonthTokens(normalized, YEAR_MONTH_UNDERSCORE, currentMonth);
    return replaceMonthTokens(normalized, YEAR_MONTH_COMPACT, currentMonth);
  }

  private static String replaceMonthTokens(String value, Pattern pattern, YearMonth currentMonth) {
    Matcher matcher = pattern.matcher(value);
    StringBuilder normalized = new StringBuilder();
    while (matcher.find()) {
      int year = Integer.parseInt(matcher.group(1));
      int month = Integer.parseInt(matcher.group(2));
      if (month < 1 || month > 12) {
        matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      long offset = ChronoUnit.MONTHS.between(currentMonth, YearMonth.of(year, month));
      if (Math.abs(offset) > WINDOW_MONTHS) {
        matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      matcher.appendReplacement(
          normalized, Matcher.quoteReplacement("ROLLING_MONTH_" + String.format("%+d", offset)));
    }
    matcher.appendTail(normalized);
    return normalized.toString();
  }
}
