package io.github.pinpols.batch.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-023 §决策 §Cutoff Schedule — 半天 / 多 cutoff 解析。
 *
 * <p>{@link #resolveCutoffTime(String, LocalDate, LocalTime)} 输入：
 *
 * <ul>
 *   <li>{@code cutoffSchedule} JSONB 字符串（schema 见 ADR-023 §决策）；
 *   <li>{@code bizDate} 当前批次日；
 *   <li>{@code defaultCutoffTime} business_calendar.cutoff_time 回退。
 * </ul>
 *
 * <p>解析顺序：
 *
 * <ol>
 *   <li>schedule 为空 / 解析失败 → 返回 defaultCutoffTime；
 *   <li>overrides 中匹配 {@code date == bizDate} 的精确 override 优先；
 *   <li>否则匹配 {@code weekdayPattern} + {@code from-to} 范围；
 *   <li>都没有 → schedule.default；
 *   <li>schedule.default 缺失 → defaultCutoffTime。
 * </ol>
 */
@Slf4j
@Service
public class CutoffScheduleResolver {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /** 解析 cutoff time。schedule 错误时记 warn 并退化到 defaultCutoffTime。 */
  public LocalTime resolveCutoffTime(
      String cutoffSchedule, LocalDate bizDate, LocalTime defaultCutoffTime) {
    if (!Texts.hasText(cutoffSchedule) || bizDate == null) {
      return defaultCutoffTime;
    }
    Map<String, Object> schedule;
    try {
      schedule = JsonUtils.fromJson(cutoffSchedule, MAP_TYPE);
    } catch (Exception parseFailure) {
      LocalDate failedBizDate = bizDate;
      String failedSchedule = cutoffSchedule;
      String causeSummary = SwallowedExceptionLogger.summary(parseFailure);
      log.warn(
          "cutoff_schedule parse failed: bizDate={}, schedule={}, msg={}",
          failedBizDate,
          failedSchedule,
          causeSummary);
      return defaultCutoffTime;
    }
    if (schedule == null) {
      return defaultCutoffTime;
    }

    LocalTime override = findOverride(schedule, bizDate);
    if (override != null) {
      return override;
    }
    Object dft = schedule.get("default");
    if (dft != null) {
      String defaultTimeText = dft.toString();
      LocalTime parsed = parseLocalTime(defaultTimeText);
      if (parsed != null) {
        return parsed;
      }
    }
    return defaultCutoffTime;
  }

  private LocalTime findOverride(Map<String, Object> schedule, LocalDate bizDate) {
    Object overrides = schedule.get("overrides");
    if (!(overrides instanceof List<?> rawList)) {
      return null;
    }
    LocalTime exactMatch = null;
    LocalTime weekdayMatch = null;
    for (Object item : rawList) {
      if (!(item instanceof Map<?, ?> map)) continue;
      Object cutoffValue = map.get("cutoff");
      if (cutoffValue == null) continue;
      String cutoffTimeText = cutoffValue.toString();
      LocalTime cutoffTime = parseLocalTime(cutoffTimeText);
      if (cutoffTime == null) continue;

      Object dateValue = map.get("date");
      if (dateValue != null) {
        String overrideDateText = dateValue.toString();
        LocalDate date = parseLocalDate(overrideDateText);
        if (date != null && date.equals(bizDate)) {
          // 精确日期匹配优先；记录后继续扫（多个精确同一天 → 取最后一条；行为可接受）
          exactMatch = cutoffTime;
        }
        continue;
      }
      Object weekdayValue = map.get("weekdayPattern");
      if (weekdayValue == null) continue;
      String weekdayText = weekdayValue.toString();
      DayOfWeek weekday = parseDayOfWeek(weekdayText);
      if (weekday == null || !weekday.equals(bizDate.getDayOfWeek())) continue;
      String fromDateText = stringValue(map.get("from"));
      LocalDate from = parseLocalDate(fromDateText);
      String toDateText = stringValue(map.get("to"));
      LocalDate to = parseLocalDate(toDateText);
      if (from != null && bizDate.isBefore(from)) continue;
      if (to != null && bizDate.isAfter(to)) continue;
      weekdayMatch = cutoffTime;
    }
    return exactMatch != null ? exactMatch : weekdayMatch;
  }

  private LocalTime parseLocalTime(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      String trimmedTime = text.trim();
      return LocalTime.parse(trimmedTime);
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid time: {}", text);
      return null;
    }
  }

  private LocalDate parseLocalDate(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      String trimmedDate = text.trim();
      return LocalDate.parse(trimmedDate);
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid date: {}", text);
      return null;
    }
  }

  private DayOfWeek parseDayOfWeek(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      String trimmedWeekday = text.trim();
      String normalizedWeekday = trimmedWeekday.toUpperCase(Locale.ROOT);
      return DayOfWeek.valueOf(normalizedWeekday);
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid weekday: {}", text);
      return null;
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
