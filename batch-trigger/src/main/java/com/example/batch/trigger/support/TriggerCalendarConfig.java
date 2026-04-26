package com.example.batch.trigger.support;

import java.time.LocalTime;
import lombok.Data;

/**
 * 触发器日历配置数据对象，承载从数据库读取的业务日历元数据。 包含时区、日切时间（{@code cutoffTime}）和节假日顺延规则（{@code holidayRollRule}），
 * 用于在调度时计算实际业务日期；该对象仅作数据载体，不包含业务逻辑。
 */
@Data
public class TriggerCalendarConfig {

  private Long id;
  private String tenantId;
  private String calendarCode;
  private String timezone;
  private String holidayRollRule;
  private LocalTime cutoffTime;
}
