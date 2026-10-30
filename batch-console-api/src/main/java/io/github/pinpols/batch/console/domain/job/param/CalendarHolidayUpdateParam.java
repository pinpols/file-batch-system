package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;

/** 节假日维护参数；SQL同时限定主键和已核对归属的父日历。 */
@Builder
public record CalendarHolidayUpdateParam(
    Long id,
    Long calendarId,
    String bizDate,
    String dayType,
    String holidayName,
    String description) {}
