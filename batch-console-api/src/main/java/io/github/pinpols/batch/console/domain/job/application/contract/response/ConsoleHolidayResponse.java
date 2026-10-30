package io.github.pinpols.batch.console.domain.job.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarHolidayRow;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 日历节假日响应（CalendarHolidayMapper 行投影，键为 camelCase 别名）。
 *
 * <p>历史 Map 响应会省略 null 列，使用 {@code NON_NULL} 保持相同键集。
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

  public static ConsoleHolidayResponse from(CalendarHolidayRow row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    return new ConsoleHolidayResponse(
        row.id(),
        row.calendarId(),
        row.bizDate(),
        row.dayType(),
        row.holidayName(),
        row.description(),
        row.createdAt(),
        row.updatedAt());
  }
}
