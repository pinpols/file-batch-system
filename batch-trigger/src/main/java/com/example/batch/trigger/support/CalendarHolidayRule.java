package com.example.batch.trigger.support;

import java.time.LocalDate;
import lombok.Data;

/**
 * 日历节假日规则数据对象，表示某一具体业务日期的日类型标记。
 * {@code dayType} 区分节假日（HOLIDAY）与调班工作日（WORKDAY_OVERRIDE）等类型，
 * 由 Mapper 从数据库读取后，由上层服务汇总构建 {@link CalendarBizDateDefinition}。
 */
@Data
public class CalendarHolidayRule {

  private LocalDate bizDate;
  private String dayType;
}
