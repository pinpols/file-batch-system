package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;

/** HTTP及JSON导入的节假日行；日期沿用字符串，由SQL显式转换。 */
@Builder
public record CalendarHolidayBatchInsertParam(
    String tenantId,
    Long calendarId,
    String bizDate,
    String dayType,
    String holidayName,
    String description) {}
