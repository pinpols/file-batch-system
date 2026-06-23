package io.github.pinpols.batch.console.domain.job.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class JobDefinitionEntity {

  private Long id;
  private String tenantId;
  private String jobCode;
  private String jobName;
  private String jobType;
  private String bizType;
  private String queueCode;
  private String workerGroup;
  private String scheduleType;
  private String scheduleExpr;
  private String timezone;
  private String calendarCode;
  private String windowCode;
  private String triggerMode;
  private Boolean dagEnabled;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String shardStrategy;

  /** ExecutionMode 枚举 code:FULL / INCREMENTAL / CDC,默认 FULL,见 V73 migration。 */
  private String executionMode;

  /** 增量模式下的水位字段名(例:update_time / id);FULL 模式下可空。 */
  private String watermarkField;

  private String executionHandler;
  private String paramSchema;
  private String defaultParams;
  private Integer priority;
  private Long version;
  private String description;
  private Boolean enabled;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
