package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 日历节假日响应（CalendarHolidayMapper 行投影，键为 camelCase 别名）。
 *
 * <p>MyBatis {@code resultType="map"} 会省略 null 列，历史 wire 不含 null 键 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleHolidayResponse(
    Long id,
    Long calendarId,
    LocalDate bizDate,
    String dayType,
    String holidayName,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public static ConsoleHolidayResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleHolidayResponse(
        JobResponseFieldReader.longValue(row, "id"),
        JobResponseFieldReader.longValue(row, "calendarId", "calendar_id"),
        JobResponseFieldReader.localDateValue(row, "bizDate", "biz_date"),
        JobResponseFieldReader.stringValue(row, "dayType", "day_type"),
        JobResponseFieldReader.stringValue(row, "holidayName", "holiday_name"),
        JobResponseFieldReader.stringValue(row, "description"),
        JobResponseFieldReader.instantValue(row, "createdAt", "created_at"),
        JobResponseFieldReader.instantValue(row, "updatedAt", "updated_at"));
  }
}
