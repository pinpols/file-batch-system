package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 克隆作业定义请求：必须指定 newJobCode，其余字段为可选覆盖。 */
@Data
public class JobDefinitionCopyRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String newJobCode;

  @Size(max = 256)
  private String jobName;

  private String workerGroup;
  private String queueCode;
  private String calendarCode;
  private String windowCode;
  private String scheduleExpr;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private Boolean enabled;
  private String description;
}
