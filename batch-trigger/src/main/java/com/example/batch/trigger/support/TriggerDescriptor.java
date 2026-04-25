package com.example.batch.trigger.support;

import lombok.Data;

/**
 * 触发器描述符，承载调度器注册一个触发器所需的全部配置信息。
 * 由 {@link com.example.batch.trigger.domain.TriggerDefinitionLoader} 从数据库加载，
 * 并传递给 {@link com.example.batch.trigger.domain.TriggerRegistrationService} 完成注册；
 * {@code enabled} 字段决定该触发器是否参与全量注册，{@code scheduleExpression} 支持 CRON 和固定频率两种格式。
 */
@Data
public class TriggerDescriptor {

  /** job_definition.id;wheel reconciler 同步 trigger_runtime_state 时用,quartz 路径不读。 */
  private Long jobDefinitionId;

  private String tenantId;
  private String jobCode;
  private String scheduleType;
  private String scheduleExpression;
  private String timezone;
  private String triggerMode;
  private String calendarCode;
  private String catchUpPolicy;
  private Integer catchUpMaxDays;
  private boolean enabled;
}
