package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class JobDefinitionUpdateRequest {
  @ValidTenantId private String tenantId;

  @Size(max = 64)
  @Pattern(
      regexp = "^$|^[a-zA-Z][a-zA-Z0-9_-]{0,63}$",
      message =
          "dependsOnJobCode must start with a letter and contain only letters, digits,"
              + " underscore or hyphen")
  private String dependsOnJobCode;

  @Size(max = 256)
  private String jobName;

  private String bizType;
  private String scheduleType;
  private String scheduleExpr;
  private String timezone;
  private String triggerMode;
  private String workerGroup;
  private String queueCode;
  private String calendarCode;
  private String windowCode;
  private Boolean dagEnabled;
  private String shardStrategy;

  /** 执行模式 ExecutionMode 枚举 code:FULL / INCREMENTAL / CDC。 */
  @Size(max = 16)
  private String executionMode;

  /** 增量模式下的水位字段名;FULL 模式下应为空。 */
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
