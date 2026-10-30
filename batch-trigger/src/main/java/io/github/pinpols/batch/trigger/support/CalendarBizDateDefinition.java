package io.github.pinpols.batch.trigger.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * 业务日历定义值对象，封装业务日期解析规则。{@code cutoffTime} 为当日实际日切边界，边界前归前一日，达到后归当天； {@code holidays}
 * 为节假日集合，{@code workdayOverrides} 为调班工作日集合， 两者共同决定最终有效业务日期。该 record 为不可变对象，创建后不应修改。
 */
public record CalendarBizDateDefinition(
    String timezone,
    LocalTime cutoffTime,
    String holidayRollRule,
    Set<LocalDate> holidays,
    Set<LocalDate> workdayOverrides) {}
