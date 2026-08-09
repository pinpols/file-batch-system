package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
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
        ConsoleResponseFieldReader.longValue(row, "id"),
        ConsoleResponseFieldReader.longValue(row, "calendarId", "calendar_id"),
        ConsoleResponseFieldReader.localDateValue(row, "bizDate", "biz_date"),
        ConsoleResponseFieldReader.stringValue(row, "dayType", "day_type"),
        ConsoleResponseFieldReader.stringValue(row, "holidayName", "holiday_name"),
        ConsoleResponseFieldReader.stringValue(row, "description"),
        ConsoleResponseFieldReader.instantValue(row, "createdAt", "created_at"),
        ConsoleResponseFieldReader.instantValue(row, "updatedAt", "updated_at"));
  }
}
