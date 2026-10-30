package io.github.pinpols.batch.console.domain.job.view;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Console 作业配置域（batch_window / business_calendar / calendar_holiday）只读查询的具名行投影。
 *
 * <p>背景：三个 Mapper 历史返回 {@code Map<String, Object>}（{@code resultType="map"}），调用方靠字符串键取值。
 * 具名 record 之后，日历导出直接读取字段；批量窗口仍在 Excel Map 边界使用 {@code toMap()}，
 * 复刻 {@code resultType="map"} 的「null 列不出键」行为。
 *
 * <p>{@code BatchWindowRow} 对应无 SQL 别名的 snake_case 键；其余行对应 camelCase 别名键。
 * {@code startTime/endTime/cutoffTime} 为 TIME 列，统一按 {@code HH:mm:ss} 字符串承载，与历史
 * {@code String.valueOf(java.sql.Time)} / {@code getString} 结果一致。
 */
public final class ConsoleJobConfigViews {

  private ConsoleJobConfigViews() {}

  /** batch_window 行（BatchWindowMapper，snake_case 键）。参数顺序即 XML constructor 映射顺序。 */
  public record BatchWindowRow(
      Long id,
      String tenantId,
      String windowCode,
      String windowName,
      String timezone,
      String startTime,
      String endTime,
      String endStrategy,
      String outOfWindowAction,
      Boolean allowCrossDay,
      Boolean enabled,
      String description,
      Instant createdAt,
      Instant updatedAt) {

    /** 复刻 resultType="map" 键集：snake_case、null 值不出键。仅用于 Excel 导出等 Map 边界。 */
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      put(map, "id", id);
      put(map, "tenant_id", tenantId);
      put(map, "window_code", windowCode);
      put(map, "window_name", windowName);
      put(map, "timezone", timezone);
      put(map, "start_time", startTime);
      put(map, "end_time", endTime);
      put(map, "end_strategy", endStrategy);
      put(map, "out_of_window_action", outOfWindowAction);
      put(map, "allow_cross_day", allowCrossDay);
      put(map, "enabled", enabled);
      put(map, "description", description);
      put(map, "created_at", createdAt);
      put(map, "updated_at", updatedAt);
      return map;
    }
  }

  /**
   * business_calendar 启用行（selectActiveByTenantAndCalendarCode，camelCase 键）。
   * 只投影调用方实际消费的 9 列；cutoffTime 为 TIME 列按字符串承载。
   */
  public record ActiveCalendarRow(
      Long id,
      String tenantId,
      String calendarCode,
      String description,
      String timezone,
      String catchUpPolicy,
      String cutoffTime,
      Integer lateArrivalToleranceMin,
      Integer slaOffsetMin) {}

  /**
   * business_calendar 完整配置行（selectByTenantAndCalendarCode / selectByQuery / selectById，
   * camelCase 键）。selectByQuery 与 selectById 带 createdAt/updatedAt；单键查询时两者为 null。
   */
  public record CalendarQueryRow(
      Long id,
      String tenantId,
      String calendarCode,
      String calendarName,
      String timezone,
      String holidayRollRule,
      String catchUpPolicy,
      Integer catchUpMaxDays,
      Boolean enabled,
      String description,
      Instant createdAt,
      Instant updatedAt) {}

  /** calendar_holiday 行（CalendarHolidayMapper，camelCase 键）。 */
  public record CalendarHolidayRow(
      Long id,
      Long calendarId,
      LocalDate bizDate,
      String dayType,
      String holidayName,
      String description,
      Instant createdAt,
      Instant updatedAt) {}

  private static void put(Map<String, Object> map, String key, Object value) {
    if (EmptyChecks.isNotNull(value)) {
      map.put(key, value);
    }
  }
}
