package io.github.pinpols.batch.console.domain.job.param;

import java.time.LocalDate;
import lombok.Builder;

/** Excel解析后的节假日行；保留LocalDate及同租户、同日历、同日期冲突语义。 */
@Builder
public record CalendarHolidayBatchUpsertParam(
    String tenantId,
    Long calendarId,
    LocalDate bizDate,
    String dayType,
    String holidayName,
    String description) {}
