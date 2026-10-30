package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 业务日历创建参数；仅生成主键由JDBC回填，其余输入保持不可变。 */
@Getter
@Builder
public class BusinessCalendarInsertParam {
  @Setter
  private Long id;

  private final String tenantId;
  private final String calendarCode;
  private final String calendarName;
  private final String timezone;
  private final String holidayRollRule;
  private final String catchUpPolicy;
  private final Integer catchUpMaxDays;
  private final Boolean enabled;
  private final String description;
}
