package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;

/** 业务日历维护参数；不改变编码或启用状态，说明由应用层保留。 */
@Builder
public record BusinessCalendarUpdateParam(
    Long id,
    String tenantId,
    String calendarName,
    String timezone,
    String holidayRollRule,
    String catchUpPolicy,
    Integer catchUpMaxDays,
    String description) {}
