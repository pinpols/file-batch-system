package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class JobDefinitionCreateRequest {
  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String jobCode;

  @Size(max = 256)
  private String jobName;

  @NotBlank private String jobType;
  private String bizType;
  @NotBlank private String scheduleType;
  private String scheduleExpr;
  private String timezone;
  private String triggerMode;
  private String workerGroup;
  private String queueCode;
  private String calendarCode;
  private String windowCode;
  private Boolean dagEnabled;
  private String shardStrategy;

  /**
   * 执行模式 ExecutionMode 枚举 code:FULL / INCREMENTAL / CDC,缺省 FULL。
   *
   * <p>INCREMENTAL 必须配合 watermarkField 才生效;CDC 当前是占位,worker 暂不实现。
   */
  @Size(max = 16)
  private String executionMode;

  /** 增量模式下的水位字段名(例:update_time / id);FULL 模式下应为空。 */
  @Size(max = 64)
  private String watermarkField;

  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private String executionHandler;
  private String paramSchema;
  private String defaultParams;
  private Integer priority;
  private Boolean enabled;
  private String description;
}
