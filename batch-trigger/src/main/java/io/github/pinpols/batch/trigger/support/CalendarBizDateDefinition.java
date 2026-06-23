package io.github.pinpols.batch.trigger.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * 业务日历定义值对象，封装计算当日业务日期所需的全部规则信息。 {@code cutoffTime} 为日切时间，超过该时间触发的任务业务日期顺延至次日； {@code holidays}
 * 为节假日集合，{@code workdayOverrides} 为调班工作日集合， 两者共同决定最终有效业务日期。该 record 为不可变对象，创建后不应修改。
 */
public record CalendarBizDateDefinition(
    String timezone,
    LocalTime cutoffTime,
    String holidayRollRule,
    Set<LocalDate> holidays,
    Set<LocalDate> workdayOverrides) {}
