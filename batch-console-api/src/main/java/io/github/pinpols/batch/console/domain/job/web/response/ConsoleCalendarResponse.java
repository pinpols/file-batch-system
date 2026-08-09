package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.time.Instant;
import java.util.Map;

/**
 * 业务日历响应（BusinessCalendarMapper 行投影，键为 camelCase 别名）。
 *
 * <p>MyBatis {@code resultType="map"} 会省略 null 列，历史 wire 不含 null 键 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleCalendarResponse(
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
    Instant updatedAt) {

  public static ConsoleCalendarResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleCalendarResponse(
        ConsoleResponseFieldReader.longValue(row, "id"),
        ConsoleResponseFieldReader.stringValue(row, "tenantId", "tenant_id"),
        ConsoleResponseFieldReader.stringValue(row, "calendarCode", "calendar_code"),
        ConsoleResponseFieldReader.stringValue(row, "calendarName", "calendar_name"),
        ConsoleResponseFieldReader.stringValue(row, "timezone"),
        ConsoleResponseFieldReader.stringValue(row, "holidayRollRule", "holiday_roll_rule"),
        ConsoleResponseFieldReader.stringValue(row, "catchUpPolicy", "catch_up_policy"),
        ConsoleResponseFieldReader.integerValue(row, "catchUpMaxDays", "catch_up_max_days"),
        ConsoleResponseFieldReader.booleanValue(row, "enabled"),
        ConsoleResponseFieldReader.stringValue(row, "description"),
        ConsoleResponseFieldReader.instantValue(row, "createdAt", "created_at"),
        ConsoleResponseFieldReader.instantValue(row, "updatedAt", "updated_at"));
  }
}
