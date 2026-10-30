package io.github.pinpols.batch.console.domain.job.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarQueryRow;
import java.time.Instant;
import lombok.Builder;

/**
 * 业务日历响应（BusinessCalendarMapper 行投影，键为 camelCase 别名）。
 *
 * <p>历史 Map 响应会省略 null 列，使用 {@code NON_NULL} 保持相同键集。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
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

  public static ConsoleCalendarResponse from(CalendarQueryRow row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    return ConsoleCalendarResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .calendarCode(row.calendarCode())
        .calendarName(row.calendarName())
        .timezone(row.timezone())
        .holidayRollRule(row.holidayRollRule())
        .catchUpPolicy(row.catchUpPolicy())
        .catchUpMaxDays(row.catchUpMaxDays())
        .enabled(row.enabled())
        .description(row.description())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }
}
