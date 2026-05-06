package com.example.batch.orchestrator.service;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.core.type.TypeReference;
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
 *   <li>{@code defaultCutoffTime} business_calendar.cutoff_time 兜底。
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
      log.warn(
          "cutoff_schedule parse failed: bizDate={}, schedule={}, msg={}",
          bizDate,
          cutoffSchedule,
          parseFailure.getMessage());
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
      LocalTime parsed = parseLocalTime(dft.toString());
      if (parsed != null) {
        return parsed;
      }
    }
    return defaultCutoffTime;
  }

  @SuppressWarnings("unchecked")
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
      LocalTime cutoffTime = parseLocalTime(cutoffValue.toString());
      if (cutoffTime == null) continue;

      Object dateValue = map.get("date");
      if (dateValue != null) {
        LocalDate date = parseLocalDate(dateValue.toString());
        if (date != null && date.equals(bizDate)) {
          // 精确日期匹配优先；记录后继续扫（多个精确同一天 → 取最后一条；行为可接受）
          exactMatch = cutoffTime;
        }
        continue;
      }
      Object weekdayValue = map.get("weekdayPattern");
      if (weekdayValue == null) continue;
      DayOfWeek weekday = parseDayOfWeek(weekdayValue.toString());
      if (weekday == null || weekday != bizDate.getDayOfWeek()) continue;
      LocalDate from = parseLocalDate(stringValue(map.get("from")));
      LocalDate to = parseLocalDate(stringValue(map.get("to")));
      if (from != null && bizDate.isBefore(from)) continue;
      if (to != null && bizDate.isAfter(to)) continue;
      weekdayMatch = cutoffTime;
    }
    return exactMatch != null ? exactMatch : weekdayMatch;
  }

  private LocalTime parseLocalTime(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return LocalTime.parse(text.trim());
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid time: {}", text);
      return null;
    }
  }

  private LocalDate parseLocalDate(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return LocalDate.parse(text.trim());
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid date: {}", text);
      return null;
    }
  }

  private DayOfWeek parseDayOfWeek(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return DayOfWeek.valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (Exception failure) {
      log.warn("cutoff_schedule invalid weekday: {}", text);
      return null;
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : value.toString();
  }
}
