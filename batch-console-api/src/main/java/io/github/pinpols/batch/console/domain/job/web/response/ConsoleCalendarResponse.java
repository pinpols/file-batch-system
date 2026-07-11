package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        JobResponseFieldReader.longValue(row, "id"),
        JobResponseFieldReader.stringValue(row, "tenantId", "tenant_id"),
        JobResponseFieldReader.stringValue(row, "calendarCode", "calendar_code"),
        JobResponseFieldReader.stringValue(row, "calendarName", "calendar_name"),
        JobResponseFieldReader.stringValue(row, "timezone"),
        JobResponseFieldReader.stringValue(row, "holidayRollRule", "holiday_roll_rule"),
        JobResponseFieldReader.stringValue(row, "catchUpPolicy", "catch_up_policy"),
        JobResponseFieldReader.integerValue(row, "catchUpMaxDays", "catch_up_max_days"),
        JobResponseFieldReader.booleanValue(row, "enabled"),
        JobResponseFieldReader.stringValue(row, "description"),
        JobResponseFieldReader.instantValue(row, "createdAt", "created_at"),
        JobResponseFieldReader.instantValue(row, "updatedAt", "updated_at"));
  }
}
